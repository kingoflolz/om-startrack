package dev.omshutter.ble;

/** Pure visibility/interactivity policy for work that can keep a hidden app awake indefinitely. */
final class BackgroundPowerPolicy {
    enum ScanMode {
        LOW_POWER,
        LOW_LATENCY
    }

    enum CorrectionMode {
        OFF,
        CONTINUE,
        SUSPEND
    }

    private BackgroundPowerPolicy() {}

    static boolean interactiveVisible(boolean activityVisible, boolean screenInteractive) {
        return activityVisible && screenInteractive;
    }

    /** SCREEN_ON may still be keyguard; only a fresh Activity resume may restore foreground. */
    static boolean applyScreenInteractiveBroadcast(boolean announcedInteractive) {
        return !announcedInteractive;
    }

    static ScanMode scanMode(boolean interactiveVisible, boolean urgentShutterRecovery) {
        return interactiveVisible || urgentShutterRecovery
                ? ScanMode.LOW_LATENCY
                : ScanMode.LOW_POWER;
    }

    static boolean deferReconnect(boolean interactiveVisible, boolean urgentShutterRecovery) {
        return !interactiveVisible && !urgentShutterRecovery;
    }

    static CorrectionMode correctionMode(
            boolean interactiveVisible,
            boolean correctionRequested,
            boolean captureCriticalWork) {
        if (!correctionRequested) return CorrectionMode.OFF;
        return interactiveVisible || captureCriticalWork
                ? CorrectionMode.CONTINUE
                : CorrectionMode.SUSPEND;
    }

    static boolean processStateTelemetry(
            boolean interactiveVisible, boolean operationallyRequired) {
        return interactiveVisible || operationallyRequired;
    }

    /** Only a bounded, urgent shutter-release recovery may stay awake across reconnect backoff. */
    static boolean holdWakeLockForReconnectBackoff(boolean urgentShutterRecovery) {
        return urgentShutterRecovery;
    }
}
