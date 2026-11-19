package dev.omshutter.ble;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.omshutter.protocol.AstroRateSetpoint;
import dev.omshutter.solver.SiderealRateCorrection;

import org.junit.Test;

public final class AstroRateLeaseTest {
    private static final int EPOCH = 9;

    @Test
    public void newerSetpointCoalescesBehindOneInFlightWrite() {
        AstroRateLease lease = new AstroRateLease();
        byte[] first = value(1);
        byte[] latest = value(2);
        lease.offerSetpoint(10L, EPOCH, first);
        AstroRateLease.Write inFlight = lease.startNext(EPOCH);
        lease.offerSetpoint(11L, EPOCH, latest);

        AstroRateLease.Completion old = lease.complete(EPOCH, true);
        assertNotNull(old);
        assertFalse(old.isLatestDesired());
        assertTrue(lease.isPossiblyActive());
        AstroRateLease.Write next = lease.startNext(EPOCH);
        assertNotNull(next);
        assertArrayEquals(latest, next.getValue());
        assertTrue(lease.complete(EPOCH, true).isLatestDesired());
    }

    @Test
    public void definitiveFailureOfStaleWriteRetainsLatestQueuedIntent() {
        AstroRateLease lease = new AstroRateLease();
        lease.offerSetpoint(10L, EPOCH, value(1));
        lease.startNext(EPOCH);
        lease.forceCancel(EPOCH, new byte[18]);

        AstroRateLease.Completion stale = lease.completeUnapplied(EPOCH);
        assertFalse(stale.isLatestDesired());
        AstroRateLease.Write latest = lease.startNext(EPOCH);
        assertNotNull(latest);
        assertTrue(latest.isCancel());
        assertArrayEquals(new byte[18], latest.getValue());
    }

    @Test
    public void renewalRequiresSuccessfulLatestAcknowledgement() {
        AstroRateLease lease = new AstroRateLease();
        lease.offerSetpoint(4L, EPOCH, value(3));

        assertFalse(lease.queueRenewal(EPOCH));
        lease.startNext(EPOCH);
        assertFalse(lease.queueRenewal(EPOCH));
        assertTrue(lease.complete(EPOCH, true).isLatestDesired());
        assertTrue(lease.queueRenewal(EPOCH));
        assertFalse(lease.queueRenewal(EPOCH));
    }

    @Test
    public void acknowledgedSetpointCanBeReplacedWithinSameGeneration() {
        AstroRateLease lease = new AstroRateLease();
        byte[] initial = value(3);
        byte[] refreshed = value(7);
        lease.offerSetpoint(4L, EPOCH, initial);
        lease.startNext(EPOCH);
        assertTrue(lease.complete(EPOCH, true).isLatestDesired());

        lease.offerSetpoint(4L, EPOCH, refreshed);
        AstroRateLease.Write replacement = lease.startNext(EPOCH);
        assertNotNull(replacement);
        assertArrayEquals(refreshed, replacement.getValue());
        assertTrue(lease.complete(EPOCH, true).isLatestDesired());

        assertTrue(lease.queueRenewal(EPOCH));
        assertArrayEquals(refreshed, lease.startNext(EPOCH).getValue());
    }

    @Test
    public void typedGavePairSurvivesQueueAndRenewal() {
        AstroRateLease lease = new AstroRateLease();
        AstroRateSetpoint setpoint = AstroRateSetpoint.gaveOnly(-64, 37);
        byte[] value = new byte[18];
        value[12] = 1;
        value[14] = (byte) -64;
        value[16] = 37;

        lease.offerSetpoint(5L, EPOCH, setpoint, value);
        AstroRateLease.Write first = lease.startNext(EPOCH);
        assertNotNull(first);
        assertTrue(setpoint.equals(first.getSetpoint()));
        assertTrue(lease.complete(EPOCH, true).isLatestDesired());
        assertTrue(lease.queueRenewal(EPOCH));
        assertTrue(setpoint.equals(lease.startNext(EPOCH).getSetpoint()));
    }

    @Test
    public void cancelSupersedesAttemptedSetpointAndMustBeAcknowledged() {
        AstroRateLease lease = new AstroRateLease();
        lease.offerSetpoint(20L, EPOCH, value(4));
        lease.startNext(EPOCH);

        assertTrue(lease.requestCancel(EPOCH, new byte[18]));
        assertTrue(lease.complete(EPOCH, true).isSuccess());
        assertTrue(lease.isPossiblyActive());
        AstroRateLease.Write cancel = lease.startNext(EPOCH);
        assertTrue(cancel.isCancel());
        assertTrue(lease.complete(EPOCH, true).getWrite().isCancel());
        assertFalse(lease.isPossiblyActive());
    }

    @Test
    public void unsentSetpointNeedsNoWireCancel() {
        AstroRateLease lease = new AstroRateLease();
        lease.offerSetpoint(20L, EPOCH, value(5));

        assertFalse(lease.requestCancel(EPOCH, new byte[18]));
        assertNull(lease.startNext(EPOCH));
        assertFalse(lease.isPossiblyActive());
    }

    @Test
    public void forcedCancelAlwaysQueuesExactZero() {
        AstroRateLease lease = new AstroRateLease();
        lease.forceCancel(EPOCH, new byte[18]);
        AstroRateLease.Write cancel = lease.startNext(EPOCH);
        assertNotNull(cancel);
        assertTrue(cancel.isCancel());
        assertArrayEquals(new byte[18], cancel.getValue());
    }

    @Test
    public void failedRenewalDoesNotArmAnotherRenewal() {
        AstroRateLease lease = new AstroRateLease();
        lease.offerSetpoint(1L, EPOCH, value(1));
        lease.startNext(EPOCH);
        lease.complete(EPOCH, true);
        lease.queueRenewal(EPOCH);
        lease.startNext(EPOCH);

        AstroRateLease.Completion failure = lease.complete(EPOCH, false);
        assertFalse(failure.isSuccess());
        assertTrue(lease.isPossiblyActive());
        assertFalse(lease.queueRenewal(EPOCH));
    }

    @Test
    public void definitiveRejectionOfFirstSetpointWasNeverActive() {
        AstroRateLease lease = new AstroRateLease();
        lease.offerSetpoint(1L, EPOCH, value(1));
        lease.startNext(EPOCH);

        assertFalse(lease.completeUnapplied(EPOCH).isSuccess());
        assertFalse(lease.isPossiblyActive());
    }

    @Test
    public void frameworkRejectedRenewalPreservesPriorActiveLease() {
        AstroRateLease lease = new AstroRateLease();
        lease.offerSetpoint(1L, EPOCH, value(1));
        lease.startNext(EPOCH);
        lease.complete(EPOCH, true);
        lease.queueRenewal(EPOCH);
        lease.startNext(EPOCH);

        lease.completeUnapplied(EPOCH);
        assertTrue(lease.isPossiblyActive());
    }

    @Test
    public void definitiveHeartbeatFailureCanRetireAcknowledgedBaselineWithExactZero() {
        AstroRateLease lease = acknowledgedLease(value(3));
        assertTrue(lease.offerHeartbeatSetpoint(
                4L, EPOCH, AstroRateSetpoint.gaveOnly(2, -1), value(7)));
        lease.startNext(EPOCH);

        AstroRateLease.Completion rejected = lease.completeUnapplied(EPOCH);
        assertTrue(rejected.getWrite().isHeartbeat());
        assertTrue(lease.isPossiblyActive());
        assertFalse(lease.isCaptureStartReady(EPOCH));
        assertFalse(lease.isCaptureStartWaitingForHeartbeat(EPOCH));

        assertTrue(lease.requestCancel(EPOCH, new byte[18]));
        assertTrue(lease.startNext(EPOCH).isCancel());
        assertTrue(lease.complete(EPOCH, true).isLatestDesired());
        assertFalse(lease.isPossiblyActive());
        // The service retires the completed cancel owner before admitting later work.
        lease.reset();
        assertTrue(lease.isCaptureStartReady(EPOCH));
    }

    @Test
    public void ambiguousFailureOfFirstSetpointRemainsPossiblyActive() {
        AstroRateLease lease = new AstroRateLease();
        lease.offerSetpoint(1L, EPOCH, value(1));
        lease.startNext(EPOCH);

        assertFalse(lease.complete(EPOCH, false).isSuccess());
        assertTrue(lease.isPossiblyActive());
    }

    @Test
    public void captureStartsOnlyFromLatestAcknowledgedQuietBoundary() {
        AstroRateLease lease = new AstroRateLease();
        assertTrue(lease.isCaptureStartReady(EPOCH));
        assertFalse(lease.beginCaptureHold(EPOCH, 100L));
        assertTrue(lease.isCaptureHeld(EPOCH, 100L));
        lease.reset();

        lease.offerSetpoint(4L, EPOCH, value(3));
        assertFalse(lease.isCaptureStartReady(EPOCH));
        lease.startNext(EPOCH);
        assertFalse(lease.isCaptureStartReady(EPOCH));
        lease.complete(EPOCH, true);
        assertTrue(lease.isCaptureStartReady(EPOCH));
        assertFalse(lease.isCaptureStartReady(EPOCH + 1));
        assertTrue(lease.beginCaptureHold(EPOCH, 100L));
        assertTrue(lease.isCaptureHeld(EPOCH, 100L));
    }

    @Test
    public void exactRenewalMayBeAwaitedWithoutWeakeningCaptureBoundary() {
        AstroRateLease queued = acknowledgedLease(value(3));
        assertTrue(queued.queueRenewal(EPOCH));
        assertFalse(queued.isCaptureStartReady(EPOCH));
        assertTrue(queued.isCaptureStartWaitingForHeartbeat(EPOCH));
        assertThrows(
                IllegalStateException.class,
                () -> queued.beginCaptureHold(EPOCH, 100L));

        queued.startNext(EPOCH);
        assertFalse(queued.isCaptureStartReady(EPOCH));
        assertTrue(queued.isCaptureStartWaitingForHeartbeat(EPOCH));
        assertThrows(
                IllegalStateException.class,
                () -> queued.beginCaptureHold(EPOCH, 100L));

        queued.complete(EPOCH, true);
        assertFalse(queued.isCaptureStartWaitingForHeartbeat(EPOCH));
        assertTrue(queued.isCaptureStartReady(EPOCH));
        assertTrue(queued.beginCaptureHold(EPOCH, 100L));
    }

    @Test
    public void adaptiveHeartbeatMayBeAwaitedQueuedOrInFlight() {
        AstroRateLease lease = acknowledgedLease(value(3));
        AstroRateSetpoint adaptive = AstroRateSetpoint.gaveOnly(4, -2);

        assertTrue(lease.offerHeartbeatSetpoint(
                4L, EPOCH, adaptive, value(8)));
        assertTrue(lease.isCaptureStartWaitingForHeartbeat(EPOCH));
        AstroRateLease.Write queued = lease.startNext(EPOCH);
        assertNotNull(queued);
        assertTrue(queued.isHeartbeat());
        assertTrue(lease.isCaptureStartWaitingForHeartbeat(EPOCH));

        assertTrue(lease.complete(EPOCH, true).isLatestDesired());
        assertTrue(lease.isCaptureStartReady(EPOCH));
        assertTrue(lease.beginCaptureHold(EPOCH, 100L));
    }

    @Test
    public void heartbeatRequiresAcknowledgedSameGenerationBaseline() {
        AstroRateLease differentGeneration = acknowledgedLease(value(3));
        assertFalse(differentGeneration.offerHeartbeatSetpoint(
                5L, EPOCH, AstroRateSetpoint.gaveOnly(1, 0), value(8)));
        assertTrue(differentGeneration.isCaptureStartReady(EPOCH));

        AstroRateLease unacknowledged = new AstroRateLease();
        unacknowledged.offerSetpoint(4L, EPOCH, value(3));
        assertFalse(unacknowledged.offerHeartbeatSetpoint(
                4L, EPOCH, AstroRateSetpoint.gaveOnly(1, 0), value(8)));
        assertFalse(unacknowledged.isCaptureStartWaitingForHeartbeat(EPOCH));
    }

    @Test
    public void replacementAndAmbiguousActiveWritesAreNotDeferredAsRenewals() {
        AstroRateLease replacement = acknowledgedLease(value(3));
        replacement.offerSetpoint(4L, EPOCH, value(9));
        assertFalse(replacement.isCaptureStartWaitingForHeartbeat(EPOCH));
        replacement.startNext(EPOCH);
        assertFalse(replacement.isCaptureStartWaitingForHeartbeat(EPOCH));

        AstroRateLease ambiguous = acknowledgedLease(value(3));
        assertTrue(ambiguous.queueRenewal(EPOCH));
        ambiguous.startNext(EPOCH);
        ambiguous.complete(EPOCH, false);
        assertFalse(ambiguous.isCaptureStartReady(EPOCH));
        assertFalse(ambiguous.isCaptureStartWaitingForHeartbeat(EPOCH));
    }

    @Test
    public void inactiveCaptureFencesFirstActiveUpdateAndRetiresItAtDone() {
        AstroRateLease lease = new AstroRateLease();
        assertFalse(lease.beginCaptureHold(EPOCH, 100L));

        byte[] firstCaptureValue = value(7);
        lease.offerCaptureSetpoint(
                8L, EPOCH, 100L, AstroRateSetpoint.gaveOnly(7, -8), firstCaptureValue);
        assertArrayEquals(firstCaptureValue, lease.startNext(EPOCH).getValue());
        assertTrue(lease.complete(EPOCH, true).isLatestDesired());
        assertTrue(lease.isPossiblyActive());

        lease.beginCaptureRetirement(EPOCH, 100L, new byte[18]);
        assertTrue(lease.startNext(EPOCH).isCancel());
        assertTrue(lease.complete(EPOCH, true).isLatestDesired());
        lease.completeCaptureRetirement(EPOCH, 100L);
        assertFalse(lease.isCaptureHeld());
        assertFalse(lease.isPossiblyActive());
    }

    @Test
    public void captureUpdateBecomesExactRenewalBaselineOnlyAfterAck() {
        AstroRateLease lease = acknowledgedLease(value(3));
        assertTrue(lease.beginCaptureHold(EPOCH, 100L));
        byte[] changed = value(9);
        AstroRateSetpoint setpoint = AstroRateSetpoint.gaveOnly(7, -8);

        lease.offerCaptureSetpoint(8L, EPOCH, 100L, setpoint, changed);
        assertFalse(lease.queueRenewal(EPOCH));
        assertArrayEquals(changed, lease.startNext(EPOCH).getValue());
        assertFalse(lease.queueRenewal(EPOCH));
        lease.complete(EPOCH, true);
        assertTrue(lease.queueRenewal(EPOCH));
        AstroRateLease.Write renewal = lease.startNext(EPOCH);
        assertArrayEquals(changed, renewal.getValue());
        assertTrue(setpoint.equals(renewal.getSetpoint()));
    }

    @Test
    public void captureUpdateCannotBeDerivedAcrossAnotherInFlightWrite() {
        AstroRateLease lease = acknowledgedLease(value(3));
        assertTrue(lease.beginCaptureHold(EPOCH, 100L));
        assertTrue(lease.queueRenewal(EPOCH));
        lease.startNext(EPOCH);

        assertThrows(
                IllegalStateException.class,
                () -> lease.offerCaptureSetpoint(
                        8L, EPOCH, 100L, AstroRateSetpoint.gaveOnly(1, 0), value(8)));
        assertThrows(
                IllegalStateException.class,
                () -> lease.offerSetpoint(8L, EPOCH, value(8)));
    }

    @Test
    public void ambiguousActiveWriteCannotSeedCaptureHold() {
        AstroRateLease lease = new AstroRateLease();
        lease.offerSetpoint(1L, EPOCH, value(1));
        lease.startNext(EPOCH);
        lease.complete(EPOCH, false);

        assertFalse(lease.isCaptureStartReady(EPOCH));
        assertThrows(
                IllegalStateException.class,
                () -> lease.beginCaptureHold(EPOCH, 100L));
    }

    @Test
    public void terminalZeroSupersedesInFlightRenewalAndFencesLaterUpdates() {
        AstroRateLease lease = acknowledgedLease(value(3));
        assertTrue(lease.beginCaptureHold(EPOCH, 100L));
        assertTrue(lease.queueRenewal(EPOCH));
        AstroRateLease.Write staleRenewal = lease.startNext(EPOCH);
        assertNotNull(staleRenewal);
        lease.beginCaptureRetirement(EPOCH, 100L, new byte[18]);

        AstroRateLease.Completion stale = lease.complete(EPOCH, true);
        assertFalse(stale.isLatestDesired());
        assertFalse(lease.queueRenewal(EPOCH));
        assertThrows(
                IllegalStateException.class,
                () -> lease.offerCaptureSetpoint(
                        9L, EPOCH, 100L, AstroRateSetpoint.gaveOnly(1, 0), value(9)));

        AstroRateLease.Write firstZero = lease.startNext(EPOCH);
        assertTrue(firstZero.isCancel());
        assertArrayEquals(new byte[18], firstZero.getValue());
        assertTrue(lease.complete(EPOCH, true).isLatestDesired());
        lease.queueCaptureRetirementRetry(EPOCH, 100L, new byte[18]);
        AstroRateLease.Write secondZero = lease.startNext(EPOCH);
        assertTrue(secondZero.isCancel());
        lease.complete(EPOCH, true);
        lease.completeCaptureRetirement(EPOCH, 100L);
        assertFalse(lease.isPossiblyActive());
        assertTrue(lease.isCaptureStartReady(EPOCH));
    }

    @Test
    public void acknowledgedMidCaptureStopKeepsFenceUntilTerminalRetirement() {
        AstroRateLease lease = acknowledgedLease(value(3));
        assertTrue(lease.beginCaptureHold(EPOCH, 100L));

        lease.forceCancel(EPOCH, new byte[18]);
        assertTrue(lease.isCaptureRetiring());
        assertTrue(lease.startNext(EPOCH).isCancel());
        assertTrue(lease.complete(EPOCH, true).isLatestDesired());
        assertTrue(lease.isCaptureHeld(EPOCH, 100L));
        assertTrue(lease.isCaptureRetiring(EPOCH, 100L));
        assertThrows(
                IllegalStateException.class,
                () -> lease.offerCaptureSetpoint(
                        9L, EPOCH, 100L, AstroRateSetpoint.gaveOnly(1, 0), value(9)));
        assertThrows(
                IllegalStateException.class,
                () -> lease.offerSetpoint(9L, EPOCH, value(9)));

        lease.queueCaptureRetirementRetry(EPOCH, 100L, new byte[18]);
        assertTrue(lease.startNext(EPOCH).isCancel());
        lease.complete(EPOCH, true);
        lease.completeCaptureRetirement(EPOCH, 100L);
        assertFalse(lease.isCaptureHeld());
    }

    @Test
    public void provenUnappliedMidCaptureStopCanBeRetriedOnlyAtTerminalBoundary() {
        AstroRateLease lease = acknowledgedLease(value(3));
        assertTrue(lease.beginCaptureHold(EPOCH, 100L));
        lease.forceCancel(EPOCH, new byte[18]);
        assertTrue(lease.startNext(EPOCH).isCancel());

        AstroRateLease.Completion rejected = lease.completeUnapplied(EPOCH);
        assertTrue(rejected.isLatestDesired());
        assertTrue(lease.isCaptureRetiring(EPOCH, 100L));
        assertFalse(lease.hasPendingWrite());
        assertFalse(lease.hasWriteInFlight());
        assertThrows(
                IllegalStateException.class,
                () -> lease.offerCaptureSetpoint(
                        9L, EPOCH, 100L, AstroRateSetpoint.gaveOnly(1, 0), value(9)));

        lease.queueCaptureRetirementRetry(EPOCH, 100L, new byte[18]);
        assertTrue(lease.startNext(EPOCH).isCancel());
        lease.complete(EPOCH, true);
        lease.completeCaptureRetirement(EPOCH, 100L);
        assertFalse(lease.isCaptureHeld());
    }

    @Test
    public void everyLeaseWriteRequiresExactRateV2Shape() {
        AstroRateLease lease = new AstroRateLease();

        assertThrows(
                IllegalArgumentException.class,
                () -> lease.offerSetpoint(1L, EPOCH, new byte[17]));
        assertThrows(
                IllegalArgumentException.class,
                () -> lease.offerSetpoint(1L, EPOCH, new byte[19]));
        assertThrows(
                IllegalArgumentException.class,
                () -> lease.forceCancel(EPOCH, new byte[17]));
    }

    private static AstroRateLease acknowledgedLease(byte[] value) {
        AstroRateLease lease = new AstroRateLease();
        lease.offerSetpoint(4L, EPOCH, value);
        lease.startNext(EPOCH);
        lease.complete(EPOCH, true);
        return lease;
    }

    private static byte[] value(int marker) {
        byte[] value = new byte[18];
        value[0] = (byte) marker;
        value[12] = 1;
        return value;
    }
}
