package dev.omshutter.ble;

/** Bounds the wakeful portion of reconnecting solely to release a possibly held shutter. */
final class ShutterRecoveryPowerWindow {
    private final long durationMs;
    private long deadlineMs;

    ShutterRecoveryPowerWindow(long durationMs) {
        if (durationMs <= 0L) throw new IllegalArgumentException("duration must be positive");
        this.durationMs = durationMs;
    }

    boolean ensureStarted(boolean recoveryRequired, long nowMs) {
        if (!recoveryRequired || deadlineMs != 0L) return false;
        deadlineMs = saturatingAdd(nowMs, durationMs);
        return true;
    }

    /** Restores one recovery episode after process recreation without granting a new window. */
    void restoreRemaining(boolean recoveryRequired, long remainingMs, long nowMs) {
        if (!recoveryRequired) {
            clear();
            return;
        }
        long boundedRemainingMs = Math.max(0L, Math.min(durationMs, remainingMs));
        // A non-zero expired sentinel prevents ensureStarted() from silently renewing the same
        // durable release episode after a sticky-service recreation.
        deadlineMs = boundedRemainingMs == 0L
                ? Math.max(1L, nowMs)
                : saturatingAdd(nowMs, boundedRemainingMs);
    }

    /** An explicit retry starts a fresh bounded window after an earlier one expired. */
    void restart(boolean recoveryRequired, long nowMs) {
        deadlineMs = recoveryRequired ? saturatingAdd(nowMs, durationMs) : 0L;
    }

    boolean isUrgent(boolean recoveryRequired, long nowMs) {
        return recoveryRequired && deadlineMs != 0L && nowMs < deadlineMs;
    }

    long remainingMs(boolean recoveryRequired, long nowMs) {
        return isUrgent(recoveryRequired, nowMs) ? deadlineMs - nowMs : 0L;
    }

    void clear() {
        deadlineMs = 0L;
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
