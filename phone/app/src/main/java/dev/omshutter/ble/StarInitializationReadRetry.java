package dev.omshutter.ble;

/**
 * Pure policy and epoch fence for retrying the idempotent STAR startup reads.
 *
 * <p>ATT {@code 0x0e} from the initial STATE/catalog fence belongs exclusively to that batch's
 * fixed retry window. An invalid handle is always terminal, and settings-verification reads
 * retain their separate mutation-confirmation deadline.
 */
final class StarInitializationReadRetry {
    static final long NO_RETRY = -1L;

    enum FailureDisposition {
        TERMINAL_INVALID_HANDLE,
        RETRY_INITIALIZATION,
        FAIL
    }

    private static final int ATT_INVALID_HANDLE = 0x01;
    private static final int ATT_UNLIKELY_ERROR = 0x0e;
    private static final int NO_EPOCH = Integer.MIN_VALUE;

    private final BoundedReadRetry backoff;
    private int retryEpoch = NO_EPOCH;

    StarInitializationReadRetry(
            long windowMs, long initialDelayMs, long maximumDelayMs) {
        backoff = new BoundedReadRetry(windowMs, initialDelayMs, maximumDelayMs);
    }

    static FailureDisposition classifyFailure(
            int attStatus,
            boolean initializationRead,
            boolean settingsVerificationRead) {
        if (attStatus == ATT_INVALID_HANDLE) {
            return FailureDisposition.TERMINAL_INVALID_HANDLE;
        }
        if (attStatus == ATT_UNLIKELY_ERROR && !settingsVerificationRead) {
            if (initializationRead) {
                return FailureDisposition.RETRY_INITIALIZATION;
            }
        }
        return FailureDisposition.FAIL;
    }

    /** Returns the next delay for this connection epoch, or {@link #NO_RETRY}. */
    long nextDelay(int connectionEpoch, long nowMs) {
        if (retryEpoch == NO_EPOCH) retryEpoch = connectionEpoch;
        if (retryEpoch != connectionEpoch) return NO_RETRY;
        return backoff.nextDelay(nowMs);
    }

    /** Fences a posted callback against disconnect and connection-epoch replacement. */
    boolean shouldRun(
            int scheduledEpoch, int currentEpoch, boolean connectionUsable) {
        return connectionUsable
                && retryEpoch == scheduledEpoch
                && scheduledEpoch == currentEpoch
                && backoff.isActive();
    }

    boolean isActive() {
        return backoff.isActive();
    }

    void reset() {
        retryEpoch = NO_EPOCH;
        backoff.reset();
    }
}
