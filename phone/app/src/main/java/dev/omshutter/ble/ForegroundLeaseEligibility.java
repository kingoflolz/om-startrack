package dev.omshutter.ble;

/** Pure three-way gate for a lease that may run only while the app is visibly interactive. */
final class ForegroundLeaseEligibility {
    private ForegroundLeaseEligibility() {}

    static boolean isEligible(
            boolean activityVisible,
            boolean screenInteractiveFromBroadcast,
            boolean screenInteractiveNow) {
        return activityVisible
                && screenInteractiveFromBroadcast
                && screenInteractiveNow;
    }
}
