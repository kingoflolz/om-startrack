package dev.omshutter.ble;

/**
 * One event-loop cadence slot for each periodic Astro Rate phone publication.
 *
 * <p>All methods and callbacks run on one serialized owner. The callback chooses an adaptive
 * replacement or exact renewal, so the timer itself cannot double the aggregate RATE cadence.
 */
final class AstroRateCadenceTimers {
    interface EventLoop {
        void postDelayed(Runnable runnable, long delayMs);
        void removeCallbacks(Runnable runnable);
    }

    private final EventLoop eventLoop;
    private final long publicationDelayMs;

    private Runnable publicationTimer;

    AstroRateCadenceTimers(
            EventLoop eventLoop,
            long publicationDelayMs) {
        if (eventLoop == null) throw new NullPointerException("eventLoop");
        if (publicationDelayMs <= 0L) {
            throw new IllegalArgumentException("Astro Rate cadence must be positive");
        }
        this.eventLoop = eventLoop;
        this.publicationDelayMs = publicationDelayMs;
    }

    void rearmPublication(Runnable action) {
        rearmPublicationAfter(action, publicationDelayMs);
    }

    void rearmPublicationAfter(Runnable action, long delayMs) {
        if (action == null) throw new NullPointerException("action");
        if (delayMs <= 0L) throw new IllegalArgumentException("publication delay must be positive");
        cancelPublication();
        Runnable timer = new Runnable() {
            @Override
            public void run() {
                if (publicationTimer != this) return;
                publicationTimer = null;
                action.run();
            }
        };
        publicationTimer = timer;
        eventLoop.postDelayed(timer, delayMs);
    }

    void retryPublication(Runnable action, long delayMs) {
        rearmPublicationAfter(action, delayMs);
    }

    void cancelPublication() {
        if (publicationTimer != null) eventLoop.removeCallbacks(publicationTimer);
        publicationTimer = null;
    }

    boolean hasPublicationTimer() { return publicationTimer != null; }

    static long remainingPeriodDelayMs(long periodMs, long startedMs, long nowMs) {
        if (periodMs <= 0L) throw new IllegalArgumentException("period must be positive");
        if (startedMs < 0L) return periodMs;
        long elapsedMs = Math.max(0L, nowMs - startedMs);
        return Math.max(1L, periodMs - elapsedMs);
    }
}
