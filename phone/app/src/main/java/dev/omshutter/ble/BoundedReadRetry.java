package dev.omshutter.ble;

/** Pure monotonic-time exponential backoff used only for idempotent characteristic reads. */
final class BoundedReadRetry {
    private final long windowMs;
    private final long initialDelayMs;
    private final long maximumDelayMs;
    private long deadlineMs;
    private long nextDelayMs;

    BoundedReadRetry(long windowMs, long initialDelayMs, long maximumDelayMs) {
        if (windowMs <= 0L || initialDelayMs <= 0L || maximumDelayMs < initialDelayMs) {
            throw new IllegalArgumentException("invalid retry bounds");
        }
        this.windowMs = windowMs;
        this.initialDelayMs = initialDelayMs;
        this.maximumDelayMs = maximumDelayMs;
    }

    /** Returns the next delay, or -1 when the fixed retry window has expired. */
    long nextDelay(long nowMs) {
        if (deadlineMs == 0L) {
            deadlineMs = saturatingAdd(nowMs, windowMs);
            nextDelayMs = initialDelayMs;
        }
        long remainingMs = deadlineMs - nowMs;
        if (remainingMs <= 0L) return -1L;
        long delayMs = Math.min(nextDelayMs, remainingMs);
        nextDelayMs = Math.min(maximumDelayMs, saturatingMultiplyByTwo(nextDelayMs));
        return delayMs;
    }

    boolean isActive() {
        return deadlineMs != 0L;
    }

    long remaining(long nowMs) {
        return deadlineMs == 0L ? 0L : Math.max(0L, deadlineMs - nowMs);
    }

    void reset() {
        deadlineMs = 0L;
        nextDelayMs = 0L;
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long saturatingMultiplyByTwo(long value) {
        return value > Long.MAX_VALUE / 2L ? Long.MAX_VALUE : value * 2L;
    }
}
