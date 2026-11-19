package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class StarStateDetailRecoveryTest {
    @Test
    public void completeAuthorityClearsOnlyTransientStateDiagnostics() {
        assertEquals(
                "",
                CameraConnectionService.clearRecoveredStarStateTransientDetail(
                        "Camera sent invalid settings state", ""));
        assertEquals(
                "",
                CameraConnectionService.clearRecoveredStarStateTransientDetail(
                        "STAR mutable state is temporarily unavailable", ""));

        assertEquals(
                "STAR defaults unavailable: reset failed",
                CameraConnectionService.clearRecoveredStarStateTransientDetail(
                        "STAR mutable state is temporarily unavailable", "reset failed"));

        assertEquals(
                "STAR defaults unavailable: reset failed",
                CameraConnectionService.clearRecoveredStarStateTransientDetail(
                        "STAR defaults unavailable: reset failed", "reset failed"));
        assertEquals(
                "Camera is busy; settings were not sent",
                CameraConnectionService.clearRecoveredStarStateTransientDetail(
                        "Camera is busy; settings were not sent", "reset failed"));
    }
}
