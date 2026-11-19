package dev.omshutter.ble;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.omshutter.protocol.AstroRateGattProtocol;
import dev.omshutter.solver.SiderealRateCorrection;

import org.junit.Test;

/**
 * Cross-component regression coverage for the terminal RATE retirement/authority boundary.
 *
 * <p>The Android service itself cannot be instantiated by the local JVM test suite (there is no
 * Robolectric or injectable GATT owner). This test therefore composes the two production state
 * machines that own the boundary and keeps the expected service orchestration explicit.
 */
public final class PostTerminalCorrectionFlowRegressionTest {
    private static final int EPOCH = 9;
    private static final long CAPTURE_SESSION = 100L;
    private static final long INITIAL_GENERATION = 4L;
    private static final long SOLVED_GENERATION = 5L;

    @Test
    public void doneZerosThenFailedOrIncompleteAuthorityReleaseOneSolvedRate() {
        AstroRateLease lease = acknowledgedLease(nonzeroValue(3));
        assertTrue(lease.beginCaptureHold(EPOCH, CAPTURE_SESSION));

        // DONE retires the capture-owned value with the same three acknowledged exact-zero
        // publications used by CameraConnectionService.
        lease.beginCaptureRetirement(EPOCH, CAPTURE_SESSION, new byte[18]);
        for (int attempt = 0; attempt < 3; attempt++) {
            AstroRateLease.Write zero = lease.startNext(EPOCH);
            assertTrue(zero.isCancel());
            assertArrayEquals(new byte[18], zero.getValue());
            assertTrue(lease.complete(EPOCH, true).isSuccess());
            if (attempt < 2) {
                lease.queueCaptureRetirementRetry(
                        EPOCH, CAPTURE_SESSION, new byte[18]);
            }
        }
        lease.completeCaptureRetirement(EPOCH, CAPTURE_SESSION);
        assertFalse(lease.isPossiblyActive());

        DeferredRelease release = new DeferredRelease(lease, nonzeroValue(7));

        // A failed ordered read begins the coherent STATE-A/catalogs/STATE-B recovery
        // obligation. A parsed-but-incomplete ordered STATE carries the same authority fact and
        // also cannot release the solved correction.
        assertEquals(
                PostTerminalCorrectionPolicy.ReadFailureAction.BEGIN_COHERENT_BANK,
                release.observeOrderedReadFailure(false));
        assertEquals(
                PostTerminalCorrectionPolicy.AuthorityAction.REFRESH_COHERENT_BANK,
                release.observeOrderedState(false, true));
        assertEquals(
                PostTerminalCorrectionPolicy.ReadFailureAction.RETRY_COHERENT_BANK,
                release.observeOrderedReadFailure(true));
        assertNull(lease.startNext(EPOCH));

        // A complete notification is useful evidence, but notification ordering is not enough
        // to cross the terminal barrier and an in-progress coherent refresh still owns it.
        assertEquals(
                PostTerminalCorrectionPolicy.AuthorityAction.WAIT,
                release.observeNotification(true, true));
        assertNull(lease.startNext(EPOCH));

        // Only the complete ordered authority after refresh releases the deferred mutation.
        assertEquals(
                PostTerminalCorrectionPolicy.AuthorityAction.RELEASE,
                release.observeOrderedState(true, false));
        assertEquals(1, release.releaseCount);

        AstroRateLease.Write solvedRate = lease.startNext(EPOCH);
        assertFalse(solvedRate.isCancel());
        assertArrayEquals(nonzeroValue(7), solvedRate.getValue());
        assertEquals(0xff, solvedRate.getValue()[12] & 0xff);
        assertEquals(0x0f, solvedRate.getValue()[13] & 0xff);
        assertTrue(lease.complete(EPOCH, true).isLatestDesired());

        // Repeated complete STATE evidence cannot replay the same deferred correction.
        assertEquals(
                PostTerminalCorrectionPolicy.AuthorityAction.WAIT,
                release.observeOrderedState(true, false));
        assertEquals(1, release.releaseCount);
        assertNull(lease.startNext(EPOCH));
    }

    @Test
    public void completeNotificationRequestsAnOrderedReadWhenNoRefreshOwnsAuthority() {
        AstroRateLease lease = new AstroRateLease();
        DeferredRelease release = new DeferredRelease(lease, nonzeroValue(11));

        assertEquals(
                PostTerminalCorrectionPolicy.AuthorityAction.REQUEST_ORDERED_STATE,
                release.observeNotification(true, false));
        assertEquals(0, release.releaseCount);
        assertNull(lease.startNext(EPOCH));

        assertEquals(
                PostTerminalCorrectionPolicy.AuthorityAction.RELEASE,
                release.observeOrderedState(true, false));
        assertEquals(1, release.releaseCount);
        assertFalse(lease.startNext(EPOCH).isCancel());
    }

    private static AstroRateLease acknowledgedLease(byte[] value) {
        AstroRateLease lease = new AstroRateLease();
        lease.offerSetpoint(INITIAL_GENERATION, EPOCH, value);
        assertFalse(lease.startNext(EPOCH).isCancel());
        assertTrue(lease.complete(EPOCH, true).isLatestDesired());
        return lease;
    }

    private static byte[] nonzeroValue(int marker) {
        return AstroRateGattProtocol.setpoint(
                SiderealRateCorrection.fromLegacyWireCounts(marker, 0, 0),
                AstroRateGattProtocol.APP_TTL_TICKS);
    }

    private static final class DeferredRelease {
        private final AstroRateLease lease;
        private final byte[] solvedRate;
        private boolean awaitingAuthority = true;
        private int releaseCount;

        private DeferredRelease(AstroRateLease lease, byte[] solvedRate) {
            this.lease = lease;
            this.solvedRate = solvedRate;
        }

        private PostTerminalCorrectionPolicy.AuthorityAction observeNotification(
                boolean complete, boolean refreshPending) {
            return apply(PostTerminalCorrectionPolicy.afterState(
                    awaitingAuthority, true, complete, refreshPending));
        }

        private PostTerminalCorrectionPolicy.AuthorityAction observeOrderedState(
                boolean complete, boolean refreshPending) {
            return apply(PostTerminalCorrectionPolicy.afterState(
                    awaitingAuthority, false, complete, refreshPending));
        }

        private PostTerminalCorrectionPolicy.ReadFailureAction observeOrderedReadFailure(
                boolean coherentFenceRead) {
            return PostTerminalCorrectionPolicy.afterReadFailure(
                    awaitingAuthority,
                    true,
                    coherentFenceRead,
                    false,
                    false);
        }

        private PostTerminalCorrectionPolicy.AuthorityAction apply(
                PostTerminalCorrectionPolicy.AuthorityAction action) {
            if (action == PostTerminalCorrectionPolicy.AuthorityAction.RELEASE) {
                awaitingAuthority = false;
                releaseCount++;
                lease.offerSetpoint(SOLVED_GENERATION, EPOCH, solvedRate);
            }
            return action;
        }
    }
}
