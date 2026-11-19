package dev.omshutter;

/**
 * Keeps the UI locked until the service has handled a specific exposure intent.
 *
 * <p>The service and Activity use different handlers, so an older Snapshot may already be queued
 * when the user makes a selection. Matching the service's monotonic intent ID prevents that stale
 * Snapshot from clearing the local interlock.
 */
final class ExposureIntentTracker {
    enum Resolution { NONE, ACCEPTED, REJECTED }

    private boolean awaitingService;
    private long awaitingIntentId;
    private long observedIntentId;
    private boolean observedIntentAccepted;

    void begin() {
        awaitingService = true;
        awaitingIntentId = 0L;
    }

    void assign(long intentId) {
        if (!awaitingService || intentId <= 0L) {
            throw new IllegalArgumentException("intent ID must belong to an active request");
        }
        awaitingIntentId = intentId;
    }

    Resolution observe(long intentId, boolean accepted) {
        if (intentId < 0L) throw new IllegalArgumentException("intent ID is negative");

        Resolution resolution = Resolution.NONE;
        if (awaitingService
                && awaitingIntentId > 0L
                && intentId >= awaitingIntentId) {
            awaitingService = false;
            awaitingIntentId = 0L;
            resolution = accepted ? Resolution.ACCEPTED : Resolution.REJECTED;
        }

        if (intentId > observedIntentId) {
            if (!accepted && resolution == Resolution.NONE) {
                resolution = Resolution.REJECTED;
            }
            observedIntentId = intentId;
            observedIntentAccepted = accepted;
        } else if (intentId == observedIntentId) {
            if (observedIntentAccepted && !accepted) {
                resolution = Resolution.REJECTED;
            }
            observedIntentAccepted = accepted;
        }
        return resolution;
    }

    void reset(long intentId, boolean accepted) {
        if (intentId < 0L) throw new IllegalArgumentException("intent ID is negative");
        awaitingService = false;
        awaitingIntentId = 0L;
        observedIntentId = intentId;
        observedIntentAccepted = accepted;
    }

    boolean isAwaitingService() {
        return awaitingService;
    }
}
