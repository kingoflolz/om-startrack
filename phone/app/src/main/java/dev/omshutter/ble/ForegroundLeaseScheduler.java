package dev.omshutter.ble;

/**
 * Single-owner scheduler for a foreground-only, expiring camera lease.
 *
 * <p>Every method, including the completion passed to {@link Sender}, must run on the same
 * serialized event loop. The scheduler keeps at most one timer and one lease write outstanding.
 * A busy transport skips that renewal until the next ordinary cadence instead of polling or
 * creating a retry storm. Connection replacement fences a late completion from the old epoch.
 */
final class ForegroundLeaseScheduler {
    interface EventLoop {
        void postDelayed(Runnable runnable, long delayMs);
        void removeCallbacks(Runnable runnable);
    }

    interface Sender {
        /**
         * Starts one lease renewal and arranges to run {@code completion} when its GATT ownership
         * ends. Returns false without retaining the completion when the transport is busy.
         */
        boolean trySend(Runnable completion);
    }

    interface Eligibility {
        /** Fresh admission check, independent of the last lifecycle/broadcast state. */
        boolean isEligibleNow();
    }

    private final EventLoop eventLoop;
    private final Sender sender;
    private final Eligibility eligibility;
    private final Runnable onIneligible;
    private final long intervalMs;
    private final Runnable timer = this::onTimer;

    private boolean foreground;
    private boolean connectionReady;
    private boolean timerScheduled;
    private boolean writeInFlight;
    private boolean renewImmediately;
    private long connectionGeneration;

    ForegroundLeaseScheduler(
            EventLoop eventLoop,
            Sender sender,
            Eligibility eligibility,
            Runnable onIneligible,
            long intervalMs) {
        if (eventLoop == null) throw new NullPointerException("eventLoop");
        if (sender == null) throw new NullPointerException("sender");
        if (eligibility == null) throw new NullPointerException("eligibility");
        if (onIneligible == null) throw new NullPointerException("onIneligible");
        if (intervalMs <= 0L) throw new IllegalArgumentException("interval must be positive");
        this.eventLoop = eventLoop;
        this.sender = sender;
        this.eligibility = eligibility;
        this.onIneligible = onIneligible;
        this.intervalMs = intervalMs;
    }

    void setForeground(boolean foreground) {
        if (this.foreground == foreground) return;
        this.foreground = foreground;
        if (foreground) renewImmediately = true;
        reconcileTimer();
        if (!foreground) onIneligible.run();
    }

    void setConnectionReady(boolean connectionReady) {
        if (this.connectionReady == connectionReady) return;
        this.connectionReady = connectionReady;
        if (connectionReady) renewImmediately = true;
        if (!connectionReady) {
            connectionGeneration++;
            writeInFlight = false;
        }
        reconcileTimer();
    }

    boolean isForeground() {
        return foreground;
    }

    boolean isTimerScheduled() {
        return timerScheduled;
    }

    boolean isWriteInFlight() {
        return writeInFlight;
    }

    private void reconcileTimer() {
        if (!foreground || !connectionReady) {
            cancelTimer();
            return;
        }
        if (!timerScheduled && !writeInFlight) {
            timerScheduled = true;
            long delayMs = renewImmediately ? 0L : intervalMs;
            renewImmediately = false;
            eventLoop.postDelayed(timer, delayMs);
        }
    }

    private void cancelTimer() {
        if (!timerScheduled) return;
        eventLoop.removeCallbacks(timer);
        timerScheduled = false;
    }

    private void onTimer() {
        if (!timerScheduled) return;
        timerScheduled = false;
        if (!foreground || !connectionReady || writeInFlight) return;
        if (!eligibility.isEligibleNow()) {
            // A fresh system query overrides stale lifecycle/broadcast state. A later explicit
            // eligible transition must re-enable the scheduler.
            foreground = false;
            renewImmediately = true;
            onIneligible.run();
            return;
        }

        long generation = connectionGeneration;
        writeInFlight = true;
        boolean admitted = sender.trySend(() -> onWriteComplete(generation));
        if (!admitted) {
            writeInFlight = false;
            // A busy transport is not an error and does not open a rapid retry loop.
            renewImmediately = false;
            reconcileTimer();
        }
    }

    private void onWriteComplete(long generation) {
        if (generation != connectionGeneration || !writeInFlight) return;
        writeInFlight = false;
        reconcileTimer();
    }
}
