package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.omshutter.protocol.AstroRateSetpoint;

import org.junit.Test;

public final class CameraConnectionServiceAdmissionTest {
    @Test
    public void exposureMutationCannotStealDeferredSolveOrRateBoundary() {
        assertFalse(CameraConnectionService.exposureMutationBlockedBySolveOrRate(
                false, false, false, false, false));
        assertTrue(CameraConnectionService.exposureMutationBlockedBySolveOrRate(
                true, false, false, false, false));
        assertTrue(CameraConnectionService.exposureMutationBlockedBySolveOrRate(
                false, true, false, false, false));
        assertTrue(CameraConnectionService.exposureMutationBlockedBySolveOrRate(
                false, false, true, false, false));
        assertTrue(CameraConnectionService.exposureMutationBlockedBySolveOrRate(
                false, false, false, true, false));
        assertTrue(CameraConnectionService.exposureMutationBlockedBySolveOrRate(
                false, false, false, false, true));
    }

    @Test
    public void gaveOnlyCorrectionCountsAsActiveBackgroundIntent() {
        assertTrue(CameraConnectionService.hasActiveRateIntent(
                AstroRateSetpoint.gaveOnly(1, 0)));
        assertFalse(CameraConnectionService.hasActiveRateIntent(
                AstroRateSetpoint.gaveOnly(0, 0)));
        assertFalse(CameraConnectionService.hasActiveRateIntent(null));
    }

    @Test
    public void notificationWarnsWhenRetainedCorrectionIsPaused() {
        assertEquals(
                "Correction paused—open the app before capture",
                CameraConnectionService.foregroundNotificationDetail(
                        "Ready",
                        AstroRateSetpoint.gaveOnly(1, 0),
                        "Correction paused while the app is in the background"));
        assertEquals(
                "Ready",
                CameraConnectionService.foregroundNotificationDetail(
                        "Ready",
                        AstroRateSetpoint.gaveOnly(0, 0),
                        "Correction paused while the app is in the background"));
    }

    @Test
    public void hiddenTransitionAbortsOnlyLiveConnectionAttempts() {
        assertTrue(CameraConnectionService.connectionAttemptInProgress(
                CameraConnectionService.Phase.CONNECTING, false, false));
        assertTrue(CameraConnectionService.connectionAttemptInProgress(
                CameraConnectionService.Phase.DISCOVERING, false, false));
        assertTrue(CameraConnectionService.connectionAttemptInProgress(
                CameraConnectionService.Phase.SUBSCRIBING, false, false));
        assertTrue(CameraConnectionService.connectionAttemptInProgress(
                CameraConnectionService.Phase.AUTHENTICATING, false, false));
        assertTrue(CameraConnectionService.connectionAttemptInProgress(
                CameraConnectionService.Phase.ENTERING_REMOTE, false, false));
        assertTrue(CameraConnectionService.connectionAttemptInProgress(
                CameraConnectionService.Phase.SCANNING, true, false));
        assertTrue(CameraConnectionService.connectionAttemptInProgress(
                CameraConnectionService.Phase.DISCONNECTED, false, true));

        assertFalse(CameraConnectionService.connectionAttemptInProgress(
                CameraConnectionService.Phase.SCANNING, false, false));
        assertFalse(CameraConnectionService.connectionAttemptInProgress(
                CameraConnectionService.Phase.READY, false, false));
        assertFalse(CameraConnectionService.connectionAttemptInProgress(
                CameraConnectionService.Phase.WAITING_FOR_BLUETOOTH, false, false));
    }

    @Test
    public void postTerminalAuthorityRetryBackoffCanSleep() {
        assertTrue(CameraConnectionService.starInitializationBackoffMaySleep(
                true, true, true));
        assertFalse(CameraConnectionService.starInitializationBackoffMaySleep(
                true, true, false));
        assertFalse(CameraConnectionService.starInitializationBackoffMaySleep(
                true, false, true));
        assertFalse(CameraConnectionService.starInitializationBackoffMaySleep(
                false, true, true));
    }

    @Test
    public void retainedCorrectionRequiresSuccessfulStableAuthorityBeforeResume() {
        assertTrue(CameraConnectionService.backgroundCorrectionResumeAuthorityReady(
                true, false, true, false, false, false));
        assertFalse(CameraConnectionService.backgroundCorrectionResumeAuthorityReady(
                false, false, true, false, false, false));
        assertFalse(CameraConnectionService.backgroundCorrectionResumeAuthorityReady(
                true, true, true, false, false, false));
        assertFalse(CameraConnectionService.backgroundCorrectionResumeAuthorityReady(
                true, false, false, false, false, false));
        assertFalse(CameraConnectionService.backgroundCorrectionResumeAuthorityReady(
                true, false, true, true, false, false));
        assertFalse(CameraConnectionService.backgroundCorrectionResumeAuthorityReady(
                true, false, true, false, true, false));
        assertFalse(CameraConnectionService.backgroundCorrectionResumeAuthorityReady(
                true, false, true, false, false, true));
    }

    @Test
    public void recoveryBudgetRestoresFromMonotonicTimeOnlyOnTheSameBoot() {
        assertEquals(25_000L, CameraConnectionService.restoredRecoveryRemainingMs(
                120_000L, 40_000L, 7, 15_000L, 7));
        assertEquals(0L, CameraConnectionService.restoredRecoveryRemainingMs(
                120_000L, 40_000L, 7, 15_000L, 8));
        assertEquals(0L, CameraConnectionService.restoredRecoveryRemainingMs(
                120_000L, 40_000L, 7, 40_000L, 7));
        assertEquals(120_000L, CameraConnectionService.restoredRecoveryRemainingMs(
                120_000L, 900_000L, -1, 10_000L, -1));
    }
}
