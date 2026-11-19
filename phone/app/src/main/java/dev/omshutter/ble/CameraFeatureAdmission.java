package dev.omshutter.ble;

/** Pure admission policy separating stock controls from mandatory STAR snapshot readiness. */
final class CameraFeatureAdmission {
    private CameraFeatureAdmission() {}

    static boolean canUseStockControls(
            boolean connectionReady, boolean stockCharacteristicsReady, boolean controlPathIdle) {
        return connectionReady && stockCharacteristicsReady && controlPathIdle;
    }

    /** New shutter/movie/press mutations stop behind unresolved STAR recovery. */
    static boolean canStartStockMutation(
            boolean stockControlsReady,
            boolean solveCapturePending,
            boolean captureRecoveryPending) {
        return stockControlsReady && !solveCapturePending && !captureRecoveryPending;
    }

    static boolean canUseStarSettings(
            boolean stockControlsReady, boolean starTransportReady, boolean starSettingsReady) {
        return stockControlsReady && starTransportReady && starSettingsReady;
    }

    static boolean canUseStarCaptureOrSolve(
            boolean stockControlsReady, boolean starTransportReady, boolean starCaptureReady) {
        return stockControlsReady && starTransportReady && starCaptureReady;
    }
}
