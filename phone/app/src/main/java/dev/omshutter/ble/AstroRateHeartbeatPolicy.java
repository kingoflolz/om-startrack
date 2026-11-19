package dev.omshutter.ble;

/** Selects exactly one periodic Astro Rate write for a solved-correction heartbeat. */
final class AstroRateHeartbeatPolicy {
    interface Attempt {
        boolean queue();
    }

    private AstroRateHeartbeatPolicy() {}

    /**
     * Keep the planned RATE unchanged while the app prepares a pre-positioned capture.
     * Once preparation is camera-owned or the stock full press has completed, PRE no longer
     * blocks adaptive heartbeats. The caller still owns GATT, lease and solve-reference checks.
     */
    static boolean canAdaptDuringPreposition(
            boolean stockPreparing, boolean starStageRequested) {
        return !stockPreparing && !starStageRequested;
    }

    /**
     * Prefer an adaptive replacement because that active write also refreshes the CPU0 TTL.
     * Queue the exact latest-acknowledged value only when no adaptive replacement was available.
     */
    static boolean queueAdaptiveOrExact(Attempt adaptive, Attempt exact) {
        if (adaptive == null) throw new NullPointerException("adaptive");
        if (exact == null) throw new NullPointerException("exact");
        return adaptive.queue() || exact.queue();
    }
}
