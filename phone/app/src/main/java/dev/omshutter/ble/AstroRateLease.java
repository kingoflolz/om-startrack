package dev.omshutter.ble;

import dev.omshutter.protocol.AstroRateGattProtocol;
import dev.omshutter.protocol.AstroRateSetpoint;

/**
 * Pure latest-only state machine for serialized Astro Rate writes.
 *
 * <p>The Android service owns timing and GATT. This class owns only mutation ordering: one write
 * may be in flight, a newer setpoint or cancel replaces the queued value, and renewal is admitted
 * only for the most recently acknowledged setpoint.
 */
final class AstroRateLease {
    static final class Write {
        private final long token;
        private final long generation;
        private final int epoch;
        private final boolean cancel;
        private final boolean heartbeat;
        private final AstroRateSetpoint setpoint;
        private final byte[] value;

        private Write(
                long token,
                long generation,
                int epoch,
                boolean cancel,
                boolean heartbeat,
                AstroRateSetpoint setpoint,
                byte[] value) {
            this.token = token;
            this.generation = generation;
            this.epoch = epoch;
            this.cancel = cancel;
            this.heartbeat = heartbeat;
            this.setpoint = setpoint;
            this.value = value.clone();
        }

        long getGeneration() { return generation; }
        int getEpoch() { return epoch; }
        boolean isCancel() { return cancel; }
        boolean isHeartbeat() { return heartbeat; }
        AstroRateSetpoint getSetpoint() { return setpoint; }
        byte[] getValue() { return value.clone(); }
    }

    static final class Completion {
        private final Write write;
        private final boolean success;
        private final boolean latestDesired;

        private Completion(Write write, boolean success, boolean latestDesired) {
            this.write = write;
            this.success = success;
            this.latestDesired = latestDesired;
        }

        Write getWrite() { return write; }
        boolean isSuccess() { return success; }
        boolean isLatestDesired() { return latestDesired; }
    }

    private long nextToken = 1L;
    private Write desired;
    private Write pending;
    private Write inFlight;
    private Write acknowledged;
    private boolean possiblyActive;
    private boolean renewable;
    private boolean captureHeld;
    private boolean captureRetiring;
    private int captureEpoch;
    private long captureSession = -1L;

    void offerSetpoint(long generation, int epoch, byte[] value) {
        offerSetpoint(generation, epoch, null, value);
    }

    void offerSetpoint(
            long generation,
            int epoch,
            AstroRateSetpoint setpoint,
            byte[] value) {
        requireValue(value);
        if (captureHeld || captureRetiring) {
            throw new IllegalStateException(
                    "Astro Rate setpoint is owned by an active STAR capture session");
        }
        Write next = new Write(
                nextToken++, generation, epoch, false, false, setpoint, value);
        desired = next;
        pending = next;
        renewable = false;
    }

    /**
     * Queues one cadence-owned adaptive replacement from an acknowledged active baseline.
     *
     * <p>This provenance is deliberately distinct from {@link #offerSetpoint}: a solve may wait
     * for this exact same-generation heartbeat's callback, while arbitrary user/manual
     * replacements remain nondeferrable. Returns false unless the lease is at the latest
     * acknowledged quiet boundary for this connection and generation.
     */
    boolean offerHeartbeatSetpoint(
            long generation,
            int epoch,
            AstroRateSetpoint setpoint,
            byte[] value) {
        requireValue(value);
        if (captureHeld || captureRetiring
                || !possiblyActive || !renewable
                || desired == null || desired.cancel
                || acknowledged == null
                || desired.token != acknowledged.token
                || desired.epoch != epoch || acknowledged.epoch != epoch
                || desired.generation != generation
                || acknowledged.generation != generation
                || pending != null || inFlight != null) {
            return false;
        }
        Write next = new Write(
                nextToken++, generation, epoch, false, true, setpoint, value);
        desired = next;
        pending = next;
        renewable = false;
        return true;
    }

    /**
     * Offers one intentional capture-time body/GAVE update against the server-frozen focal.
     * The caller must wait for all prior RATE writes to finish before deriving this new value.
     */
    void offerCaptureSetpoint(
            long generation,
            int epoch,
            long session,
            AstroRateSetpoint setpoint,
            byte[] value) {
        requireValue(value);
        if (!isCaptureHeld(epoch, session) || captureRetiring
                || pending != null || inFlight != null) {
            throw new IllegalStateException(
                    "Astro Rate capture update is not at a serialized boundary");
        }
        Write next = new Write(
                nextToken++, generation, epoch, false, false, setpoint, value);
        desired = next;
        pending = next;
        renewable = false;
    }

    /**
     * Requests one exact-zero cancel when an acknowledged or attempted setpoint may be live.
     * Returns false when an unsent queued setpoint was simply discarded.
     */
    boolean requestCancel(int epoch, byte[] exactZeroValue) {
        requireValue(exactZeroValue);
        for (byte octet : exactZeroValue) {
            if (octet != 0) throw new IllegalArgumentException("cancel value is not all zero");
        }
        if (captureHeld) {
            captureRetiring = true;
        } else {
            captureRetiring = false;
            captureSession = -1L;
        }
        if (desired != null && desired.cancel) return true;
        boolean attemptedSetpoint = inFlight != null && !inFlight.cancel;
        if (!possiblyActive && !attemptedSetpoint) {
            desired = null;
            pending = null;
            renewable = false;
            return false;
        }
        long generation = desired == null ? -1L : desired.generation;
        Write cancel = new Write(
                nextToken++, generation, epoch, true, false, null, exactZeroValue);
        desired = cancel;
        pending = cancel;
        renewable = false;
        return true;
    }

    /** Always queues one exact-zero cancellation, including after process-local state loss. */
    void forceCancel(int epoch, byte[] exactZeroValue) {
        requireExactZero(exactZeroValue);
        if (captureHeld) {
            captureRetiring = true;
        } else {
            captureRetiring = false;
            captureSession = -1L;
        }
        long generation = desired == null ? -1L : desired.generation;
        Write cancel = new Write(
                nextToken++, generation, epoch, true, false, null, exactZeroValue);
        desired = cancel;
        pending = cancel;
        renewable = false;
    }

    /** Queues a renewal only for the latest successfully acknowledged setpoint. */
    boolean queueRenewal(int epoch) {
        if (!renewable || !possiblyActive || desired == null || desired.cancel
                || desired.epoch != epoch || pending != null || inFlight != null) {
            return false;
        }
        Write renewal = new Write(
                nextToken++,
                desired.generation,
                epoch,
                false,
                true,
                desired.setpoint,
                desired.value);
        desired = renewal;
        pending = renewal;
        renewable = false;
        return true;
    }

    /**
     * True when a STAR capture may start without racing an unacknowledged RATE mutation.
     *
     * <p>An inactive lease is immediately ready. An active lease is ready only at the quiet point
     * after its latest exact value was acknowledged and before another renewal or replacement was
     * queued. This is the value from which {@link #beginCaptureHold(int, long)} starts the
     * capture-scoped mutation authority.
     */
    boolean isCaptureStartReady(int epoch) {
        if (captureHeld) return false;
        boolean hasIntent = desired != null || pending != null || inFlight != null
                || possiblyActive || acknowledged != null;
        if (!hasIntent) return true;
        return possiblyActive
                && renewable
                && acknowledged != null
                && desired != null
                && desired.token == acknowledged.token
                && desired.epoch == epoch
                && pending == null
                && inFlight == null;
    }

    /**
     * True when only one cadence heartbeat from the acknowledged active value blocks capture.
     *
     * <p>This does not weaken {@link #isCaptureStartReady(int)}: the caller must stop future
     * cadence and wait for this queued or in-flight heartbeat's successful callback before taking
     * the capture hold. Arbitrary replacement values and ambiguous active state are never
     * deferrable. A heartbeat may be either an exact renewal or an explicitly provenance-marked
     * same-generation adaptive replacement.
     */
    boolean isCaptureStartWaitingForHeartbeat(int epoch) {
        if (captureHeld || captureRetiring || !possiblyActive
                || acknowledged == null || acknowledged.epoch != epoch
                || desired == null || desired.cancel || !desired.heartbeat
                || desired.epoch != epoch
                || desired.generation != acknowledged.generation) {
            return false;
        }
        boolean latestIsPending = pending != null
                && inFlight == null
                && pending.token == desired.token;
        boolean latestIsInFlight = inFlight != null
                && pending == null
                && inFlight.token == desired.token;
        return latestIsPending || latestIsInFlight;
    }

    /**
     * Binds one STAR capture to the exact latest acknowledged value and connection epoch.
     *
     * <p>The session fence is installed even when no RATE value is active yet. Capture-time body
     * or GAVE updates may then be admitted through {@link #offerCaptureSetpoint}; terminal cleanup
     * still owns an exact-zero retirement if one of those updates reaches the camera.
     *
     * @return true when an active value was present at the boundary; false when capture starts
     *         with an inactive RATE lease
     * @throws IllegalStateException when a RATE mutation or ambiguous write prevents a safe hold
     */
    boolean beginCaptureHold(int epoch, long session) {
        if (!isCaptureStartReady(epoch)) {
            throw new IllegalStateException(
                    "Astro Rate is not at an acknowledged capture boundary");
        }
        boolean wasActive = possiblyActive;
        captureHeld = true;
        captureEpoch = epoch;
        captureSession = session;
        return wasActive;
    }

    boolean isCaptureHeld() { return captureHeld; }

    boolean isCaptureHeld(int epoch, long session) {
        return captureHeld && captureEpoch == epoch && captureSession == session;
    }

    /** Queues the terminal exact zero while retaining the capture mutation fence. */
    void beginCaptureRetirement(
            int epoch, long session, byte[] exactZeroValue) {
        requireExactZero(exactZeroValue);
        if (!isCaptureHeld(epoch, session) || captureRetiring) {
            throw new IllegalStateException("Astro Rate capture hold is not active");
        }
        captureRetiring = true;
        Write cancel = new Write(
                nextToken++, desired == null ? -1L : desired.generation,
                epoch, true, false, null, exactZeroValue);
        desired = cancel;
        pending = cancel;
        renewable = false;
    }

    /** Queues a later exact-zero attempt only while the same terminal retirement owns the lease. */
    void queueCaptureRetirementRetry(
            int epoch, long session, byte[] exactZeroValue) {
        requireExactZero(exactZeroValue);
        if (!captureRetiring || !isCaptureHeld(epoch, session)
                || pending != null || inFlight != null) {
            throw new IllegalStateException("Astro Rate capture retirement is not retryable");
        }
        Write cancel = new Write(
                nextToken++, desired == null ? -1L : desired.generation,
                epoch, true, false, null, exactZeroValue);
        desired = cancel;
        pending = cancel;
        renewable = false;
    }

    boolean isCaptureRetiring(int epoch, long session) {
        return captureRetiring && isCaptureHeld(epoch, session);
    }

    boolean isCaptureRetiring() { return captureRetiring; }

    /** Releases the mutation fence after the complete acknowledged terminal-zero sequence. */
    void completeCaptureRetirement(int epoch, long session) {
        if (!isCaptureRetiring(epoch, session) || pending != null || inFlight != null
                || desired == null || !desired.cancel || possiblyActive) {
            throw new IllegalStateException("Astro Rate capture retirement is incomplete");
        }
        reset();
    }

    Write startNext(int epoch) {
        if (inFlight != null || pending == null) return null;
        if (pending.epoch != epoch) {
            reset();
            return null;
        }
        inFlight = pending;
        pending = null;
        return inFlight;
    }

    Completion complete(int epoch, boolean success) {
        if (inFlight == null || inFlight.epoch != epoch) return null;
        Write completed = inFlight;
        inFlight = null;
        boolean latest = desired != null && desired.token == completed.token;
        if (success) {
            possiblyActive = !completed.cancel;
            if (completed.cancel) {
                acknowledged = null;
            } else if (latest) {
                acknowledged = completed;
            }
        } else if (!completed.cancel) {
            // Android can report transport failure after the peer has observed a write. Keep
            // cancellation conservative until disconnect or an acknowledged all-zero value.
            possiblyActive = true;
        }
        renewable = success && latest && !completed.cancel;
        return new Completion(completed, success, latest);
    }

    /** Completes a write proven not to have been applied by either endpoint. */
    Completion completeUnapplied(int epoch) {
        if (inFlight == null || inFlight.epoch != epoch) return null;
        Write completed = inFlight;
        inFlight = null;
        boolean latest = desired != null && desired.token == completed.token;
        renewable = false;
        return new Completion(completed, false, latest);
    }

    boolean hasWriteInFlight() { return inFlight != null; }
    boolean hasPendingWrite() { return pending != null; }
    boolean isPossiblyActive() {
        return possiblyActive || (inFlight != null && !inFlight.cancel);
    }
    boolean wantsSetpoint() { return desired != null && !desired.cancel; }
    long getDesiredGeneration() { return desired == null ? -1L : desired.generation; }

    /** Drops automatic/retry intent while preserving whether the peer may still be active. */
    void abandonWrites() {
        desired = null;
        pending = null;
        inFlight = null;
        acknowledged = null;
        renewable = false;
        captureHeld = false;
        captureRetiring = false;
        captureSession = -1L;
    }

    void reset() {
        desired = null;
        pending = null;
        inFlight = null;
        acknowledged = null;
        possiblyActive = false;
        renewable = false;
        captureHeld = false;
        captureRetiring = false;
        captureSession = -1L;
    }

    private static void requireValue(byte[] value) {
        if (value == null || value.length != AstroRateGattProtocol.VALUE_BYTES) {
            throw new IllegalArgumentException(
                    "Astro Rate write value must contain exactly "
                            + AstroRateGattProtocol.VALUE_BYTES + " bytes");
        }
    }

    private static void requireExactZero(byte[] value) {
        requireValue(value);
        for (byte octet : value) {
            if (octet != 0) throw new IllegalArgumentException("cancel value is not all zero");
        }
    }
}
