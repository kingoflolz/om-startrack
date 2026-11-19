package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BackgroundPowerPolicyTest {
    @Test
    public void foregroundPowerPolicyRequiresVisibleActivityAndInteractiveScreen() {
        assertTrue(BackgroundPowerPolicy.interactiveVisible(true, true));
        assertFalse(BackgroundPowerPolicy.interactiveVisible(true, false));
        assertFalse(BackgroundPowerPolicy.interactiveVisible(false, true));
        assertFalse(BackgroundPowerPolicy.interactiveVisible(false, false));
    }

    @Test
    public void screenOnNeverSupersedesFreshActivityVisibilityAuthority() {
        assertFalse(BackgroundPowerPolicy.applyScreenInteractiveBroadcast(true));
        assertTrue(BackgroundPowerPolicy.applyScreenInteractiveBroadcast(false));
    }

    @Test
    public void visibleForegroundKeepsFastReconnectAndCorrection() {
        assertEquals(
                BackgroundPowerPolicy.ScanMode.LOW_LATENCY,
                BackgroundPowerPolicy.scanMode(true, false));
        assertFalse(BackgroundPowerPolicy.deferReconnect(true, false));
        assertEquals(
                BackgroundPowerPolicy.CorrectionMode.CONTINUE,
                BackgroundPowerPolicy.correctionMode(true, true, false));
        assertTrue(BackgroundPowerPolicy.processStateTelemetry(true, false));
    }

    @Test
    public void hiddenIdleQuiescesBackgroundWork() {
        assertEquals(
                BackgroundPowerPolicy.ScanMode.LOW_POWER,
                BackgroundPowerPolicy.scanMode(false, false));
        assertTrue(BackgroundPowerPolicy.deferReconnect(false, false));
        assertEquals(
                BackgroundPowerPolicy.CorrectionMode.SUSPEND,
                BackgroundPowerPolicy.correctionMode(false, true, false));
        assertFalse(BackgroundPowerPolicy.processStateTelemetry(false, false));
        assertFalse(BackgroundPowerPolicy.holdWakeLockForReconnectBackoff(false));
    }

    @Test
    public void hiddenCaptureKeepsCorrectionAndTelemetryAlive() {
        assertEquals(
                BackgroundPowerPolicy.CorrectionMode.CONTINUE,
                BackgroundPowerPolicy.correctionMode(false, true, true));
        assertTrue(BackgroundPowerPolicy.processStateTelemetry(false, true));
    }

    @Test
    public void hiddenShutterRecoveryKeepsReconnectUrgent() {
        assertEquals(
                BackgroundPowerPolicy.ScanMode.LOW_LATENCY,
                BackgroundPowerPolicy.scanMode(false, true));
        assertFalse(BackgroundPowerPolicy.deferReconnect(false, true));
        assertTrue(BackgroundPowerPolicy.holdWakeLockForReconnectBackoff(true));
    }

    @Test
    public void disabledCorrectionStaysOff() {
        assertEquals(
                BackgroundPowerPolicy.CorrectionMode.OFF,
                BackgroundPowerPolicy.correctionMode(false, false, true));
        assertEquals(
                BackgroundPowerPolicy.CorrectionMode.OFF,
                BackgroundPowerPolicy.correctionMode(true, false, false));
    }
}
