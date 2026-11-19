package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.omshutter.protocol.ExposureCatalogs;
import dev.omshutter.storage.CaptureRecoveryStore;

import org.junit.Test;

public final class CaptureRestoreBarrierTest {
    @Test
    public void cachedPreTerminalStateCannotDischargeTheObligation() {
        CaptureRestoreBarrier barrier = new CaptureRestoreBarrier();
        CaptureRecoveryStore.Obligation obligation = obligation();
        barrier.arm(obligation);

        assertEquals(
                CaptureRestoreBarrier.Observation.IGNORED,
                barrier.observe("camera", obligation.getSelection()));
        assertTrue(barrier.isBlocking());
    }

    @Test
    public void onlyExactOrderedPostTerminalTupleMatches() {
        CaptureRestoreBarrier barrier = new CaptureRestoreBarrier();
        CaptureRecoveryStore.Obligation obligation = obligation();
        barrier.arm(obligation);
        barrier.requirePostTerminalState();

        assertEquals(
                CaptureRestoreBarrier.Observation.IGNORED,
                barrier.observePostTerminalState(
                        "camera", obligation.getSelection(), false));
        assertTrue(barrier.isBlocking());
        assertEquals(
                CaptureRestoreBarrier.Observation.MISMATCH,
                barrier.observePostTerminalState(
                        "other-camera", obligation.getSelection(), true));
        assertEquals(
                CaptureRestoreBarrier.Observation.MISMATCH,
                barrier.observePostTerminalState(
                        "camera",
                        new ExposureCatalogs.Selection("1\"", "F4.0", "800"),
                        true));
        assertEquals(
                CaptureRestoreBarrier.Observation.MATCHED,
                barrier.observePostTerminalState(
                        "camera", obligation.getSelection(), true));
        // Persistence must clear successfully before the service calls clear().
        assertTrue(barrier.isBlocking());
        barrier.clear();
        assertFalse(barrier.isBlocking());
    }

    @Test
    public void certifiedTerminalClearsOnlyAfterDurableMarkerClears() {
        CaptureRestoreBarrier cleared = new CaptureRestoreBarrier();
        cleared.arm(obligation());
        cleared.finishTerminal(true, true);
        assertFalse(cleared.isBlocking());

        CaptureRestoreBarrier durableClearFailed = new CaptureRestoreBarrier();
        durableClearFailed.arm(obligation());
        durableClearFailed.finishTerminal(true, false);
        assertTrue(durableClearFailed.isRecoveryRequired());
        assertEquals(
                CaptureRestoreBarrier.Observation.MATCHED,
                durableClearFailed.observePostTerminalState(
                        "camera", obligation().getSelection(), true));
    }

    @Test
    public void unsafeOrUncertifiedTerminalAlwaysRequiresExactOrderedState() {
        CaptureRestoreBarrier unsafe = new CaptureRestoreBarrier();
        unsafe.arm(obligation());
        // Even an impossible/erroneous 'cleared' input cannot make an uncertified result safe.
        unsafe.finishTerminal(false, true);

        assertTrue(unsafe.isRecoveryRequired());
        assertEquals(
                CaptureRestoreBarrier.Observation.IGNORED,
                unsafe.observePostTerminalState(
                        "camera", obligation().getSelection(), false));
        assertEquals(
                CaptureRestoreBarrier.Observation.MATCHED,
                unsafe.observePostTerminalState(
                        "camera", obligation().getSelection(), true));
    }

    @Test
    public void processRestartRestoresPendingAndCorruptRecordsFailClosed() {
        CaptureRestoreBarrier pending = new CaptureRestoreBarrier();
        pending.restorePending(obligation());
        assertTrue(pending.isRecoveryRequired());
        assertEquals(
                CaptureRestoreBarrier.Observation.MATCHED,
                pending.observePostTerminalState(
                        "camera", obligation().getSelection(), true));

        CaptureRestoreBarrier corrupt = new CaptureRestoreBarrier();
        corrupt.restoreUnknown();
        assertTrue(corrupt.isBlocking());
        assertEquals(
                CaptureRestoreBarrier.Observation.BLOCKED_UNKNOWN,
                corrupt.observe("camera", obligation().getSelection()));
    }

    @Test
    public void freshConnectionCanAdoptDifferentCoherentTupleForSameCamera() {
        CaptureRestoreBarrier barrier = new CaptureRestoreBarrier();
        barrier.restorePending(obligation());
        ExposureCatalogs.Selection current =
                new ExposureCatalogs.Selection("2\"", "F4.5", "250");

        // Restoring a process record alone is not permission for an ordinary same-session STATE.
        assertEquals(
                CaptureRestoreBarrier.Observation.IGNORED,
                barrier.observeFreshConnectionState("camera", current, true));
        barrier.beginFreshConnection();
        assertTrue(barrier.canAcceptFreshConnectionBaseline());
        assertEquals(
                CaptureRestoreBarrier.Observation.FRESH_BASELINE,
                barrier.observeFreshConnectionState("camera", current, true));
        // Persistence must clear successfully before the service calls clear().
        assertTrue(barrier.isBlocking());
        barrier.clear();
        assertFalse(barrier.isBlocking());
    }

    @Test
    public void currentConnectionUnsafeRecoveryCannotAdoptDifferentTuple() {
        CaptureRestoreBarrier barrier = new CaptureRestoreBarrier();
        barrier.arm(obligation());
        barrier.finishTerminal(false, false);

        assertFalse(barrier.canAcceptFreshConnectionBaseline());
        assertEquals(
                CaptureRestoreBarrier.Observation.IGNORED,
                barrier.observeFreshConnectionState(
                        "camera", new ExposureCatalogs.Selection("2\"", "F4.5", "250"), true));
        assertEquals(
                CaptureRestoreBarrier.Observation.MISMATCH,
                barrier.observePostTerminalState(
                        "camera",
                        new ExposureCatalogs.Selection("2\"", "F4.5", "250"),
                        true));
        assertTrue(barrier.isBlocking());
    }

    @Test
    public void freshBaselineRejectsIncoherentStateDifferentCameraAndCorruptRecord() {
        CaptureRestoreBarrier valid = new CaptureRestoreBarrier();
        valid.restorePending(obligation());
        valid.beginFreshConnection();

        assertEquals(
                CaptureRestoreBarrier.Observation.IGNORED,
                valid.observeFreshConnectionState(
                        "camera", obligation().getSelection(), false));
        assertEquals(
                CaptureRestoreBarrier.Observation.MISMATCH,
                valid.observeFreshConnectionState(
                        "camera-b", obligation().getSelection(), true));
        assertTrue(valid.isBlocking());

        CaptureRestoreBarrier corrupt = new CaptureRestoreBarrier();
        corrupt.restoreUnknown();
        corrupt.beginFreshConnection();
        assertFalse(corrupt.canAcceptFreshConnectionBaseline());
        assertEquals(
                CaptureRestoreBarrier.Observation.BLOCKED_UNKNOWN,
                corrupt.observeFreshConnectionState(
                        "camera", obligation().getSelection(), true));
        assertTrue(corrupt.isBlocking());
    }

    @Test
    public void cameraBAndDifferentTupleCannotDischargeCameraAObligation() {
        CaptureRestoreBarrier barrier = new CaptureRestoreBarrier();
        CaptureRecoveryStore.Obligation cameraA = obligation();
        barrier.restorePending(cameraA);

        assertEquals(
                CaptureRestoreBarrier.Observation.MISMATCH,
                barrier.observe("camera-b", cameraA.getSelection()));
        assertEquals(
                CaptureRestoreBarrier.Observation.MISMATCH,
                barrier.observe("camera", new ExposureCatalogs.Selection(
                        "1/60", "F4.0", "800")));
        assertTrue(barrier.isBlocking());
    }

    @Test
    public void corruptUnknownObligationCannotBeClearedByGenericConfirmedSettings() {
        CaptureRestoreBarrier barrier = new CaptureRestoreBarrier();
        barrier.restoreUnknown();

        assertEquals(
                CaptureRestoreBarrier.Observation.BLOCKED_UNKNOWN,
                barrier.observe("camera", obligation().getSelection()));
        assertTrue(barrier.isBlocking());
        assertTrue(barrier.isBlockedUnknown());
    }

    @Test
    public void recoveryDetailNamesExpectedCurrentAndSafeAction() {
        CaptureRestoreBarrier mismatch = new CaptureRestoreBarrier();
        mismatch.restorePending(obligation());
        assertEquals(
                "STAR recovery: set Shutter mode to 1/125 / F4.0 / ISO 800 "
                        + "(currently 2\" / F4.5 / ISO 250), or reconnect or restart the camera "
                        + "to accept its fresh state as the new baseline",
                mismatch.recoveryDetail(
                        "camera",
                        new ExposureCatalogs.Selection("2\"", "F4.5", "250")));

        assertEquals(
                "STAR recovery belongs to a different camera; reconnect the configured capture "
                        + "camera (only that camera can reset recovery), expected Shutter mode "
                        + "1/125 / F4.0 / ISO 800",
                mismatch.recoveryDetail("camera-b", obligation().getSelection()));
        assertEquals(
                "STAR recovery is waiting for current camera settings; expected Shutter mode "
                        + "1/125 / F4.0 / ISO 800; reconnect or restart the camera to establish "
                        + "a fresh baseline",
                mismatch.recoveryDetail("camera", null));

        CaptureRestoreBarrier markerClearFailed = new CaptureRestoreBarrier();
        markerClearFailed.restorePending(obligation());
        assertEquals(
                "STAR recovery settings match 1/125 / F4.0 / ISO 800, but the local recovery "
                        + "marker could not be cleared; retry, or reconnect or restart the camera",
                markerClearFailed.recoveryDetail("camera", obligation().getSelection()));
    }

    @Test
    public void corruptRecoveryDetailStatesTheActualEscapePath() {
        CaptureRestoreBarrier corrupt = new CaptureRestoreBarrier();
        corrupt.restoreUnknown();

        assertEquals(
                "STAR recovery record is unreadable; manually restore the camera, then clear "
                        + "this app's storage",
                corrupt.recoveryDetail("camera", obligation().getSelection()));
    }

    private static CaptureRecoveryStore.Obligation obligation() {
        return new CaptureRecoveryStore.Obligation(
                "camera", new ExposureCatalogs.Selection("1/125", "F4.0", "800"));
    }

}
