package dev.omshutter.ble;

import dev.omshutter.protocol.GyroAttitudeProtocol;

/** Serial BLE-owner admission and capture association for camera gyro attitude. */
public final class CameraGyroTracker {
    public static final long MAX_SAMPLE_AGE_NANOS = 500_000_000L;
    public static final long MAX_CAPTURE_DELAY_NANOS = 180_000_000_000L;
    private static final long UINT32_MASK = 0xffff_ffffL;
    private static final long HALF_RANGE = 0x8000_0000L;
    private static final long NANOS_PER_TICK = 1_000_000_000L / GyroAttitudeProtocol.TICK_HZ;
    private static final long MAX_CAPTURE_TICKS = MAX_CAPTURE_DELAY_NANOS / NANOS_PER_TICK;

    private Sample latest;
    private Capture capture;
    private long confirmationGeneration = -1L;
    private boolean captureConfirmed;
    private PendingCapture pending;
    private long lastObservedReceiptNanos = -1L;
    private boolean orderingKnown;
    private long tick;

    /**
     * Returns true when a new current sample is admitted. Duplicate ticks do not renew its
     * freshness, but may contain a newly published capture latch at that same detector tick.
     */
    public boolean accept(GyroAttitudeProtocol.Snapshot snapshot, long receivedNanos) {
        if (snapshot == null) throw new IllegalArgumentException("gyro snapshot is null");
        if (receivedNanos < 0L) throw new IllegalArgumentException("negative gyro receipt time");
        if (receivedNanos < lastObservedReceiptNanos) return false;
        lastObservedReceiptNanos = receivedNanos;

        if (!snapshot.isCurrentValid()) {
            latest = null;
            matchCapture(snapshot, receivedNanos);
            return false;
        }
        if (orderingKnown) {
            long elapsedTicks = unsignedDifference(snapshot.getTick(), tick);
            if (elapsedTicks >= HALF_RANGE) {
                // Preserve the ordering fence until the source progresses beyond the last
                // accepted tick; a late older value must not resurrect a cleared attitude.
                invalidate();
                return false;
            }
            if (elapsedTicks == 0L) {
                matchCapture(snapshot, receivedNanos);
                return false;
            }
        }

        tick = snapshot.getTick();
        orderingKnown = true;
        latest = new Sample(snapshot, receivedNanos);
        matchCapture(snapshot, receivedNanos);
        return true;
    }

    /**
     * Arm immediately before CAPTURE_RECIPE using its dedicated attitude read. The read's
     * midpoint approximately associates the 2 kHz camera counter with the phone's monotonic
     * clock. Native relay cache age adds unknown delay beyond half the read round trip;
     * preparation latching also precedes exposure by an unmeasured interval. This is not an
     * exact exposure timestamp. A cache-induced future estimate is conservatively withheld.
     * A slow, stale, or regressing baseline fails closed and must not be used for this solve.
     */
    public boolean beginCapture(long solveGeneration, GyroAttitudeProtocol.Snapshot baseline,
            long requestNanos, long responseNanos) {
        if (baseline == null) throw new IllegalArgumentException("gyro baseline is null");
        if (requestNanos < 0L || responseNanos < requestNanos) {
            throw new IllegalArgumentException("invalid gyro read interval");
        }
        pending = null;
        capture = null;
        confirmationGeneration = -1L;
        captureConfirmed = false;
        long roundTripNanos = responseNanos - requestNanos;
        if (roundTripNanos > MAX_SAMPLE_AGE_NANOS
                || responseNanos < lastObservedReceiptNanos) return false;

        accept(baseline, responseNanos);
        if (!baseline.isCurrentValid() || latest == null || !isFresh(responseNanos)
                || latest.snapshot.getTick() != baseline.getTick()) return false;

        pending = new PendingCapture(solveGeneration, baseline.getCaptureSequence(), baseline.getTick(),
                requestNanos + roundTripNanos / 2L, (roundTripNanos + 1L) / 2L);
        return true;
    }

    private void matchCapture(GyroAttitudeProtocol.Snapshot snapshot, long receivedNanos) {
        if (pending == null) {
            if (capture != null && !captureConfirmed) {
                long advance = unsignedDifference(snapshot.getCaptureSequence(), capture.sequence);
                if (advance > 0L && advance < HALF_RANGE) capture = null;
            }
            return;
        }
        if (receivedNanos - pending.midpointNanos > MAX_CAPTURE_DELAY_NANOS) {
            pending = null;
            return;
        }
        long sequenceDelta = unsignedDifference(
                snapshot.getCaptureSequence(), pending.sequence);
        if (sequenceDelta == 0L) return;
        if (sequenceDelta != 1L) {
            // Two unseen captures are ambiguous; never silently attach the latest one.
            pending = null;
            return;
        }
        if (!snapshot.isCaptureValid()) return;

        long captureDelta = unsignedDifference(snapshot.getCaptureTick(), pending.tick);
        long captureAge = unsignedDifference(snapshot.getTick(), snapshot.getCaptureTick());
        if (captureDelta > MAX_CAPTURE_TICKS || captureAge >= HALF_RANGE) {
            pending = null;
            return;
        }
        long offsetNanos = captureDelta * NANOS_PER_TICK;
        if (pending.midpointNanos > Long.MAX_VALUE - offsetNanos) {
            pending = null;
            return;
        }
        long captureNanos = pending.midpointNanos + offsetNanos;
        if (captureNanos > receivedNanos
                && captureNanos - receivedNanos > pending.uncertaintyNanos) {
            pending = null;
            return;
        }
        capture = new Capture(pending.generation, snapshot.getCaptureSequence(), snapshot.getCaptureTick(),
                snapshot.getCaptureQuaternion(), captureNanos,
                snapshot.isCaptureTimingPreparation());
        pending = null;
    }

    public Sample getLatestSample() { return latest; }

    public long getLatestReceiptNanos() {
        return latest == null ? -1L : latest.receiptElapsedRealtimeNanos;
    }

    public boolean isFresh(long nowNanos) {
        return latest != null && nowNanos >= latest.receiptElapsedRealtimeNanos
                && nowNanos - latest.receiptElapsedRealtimeNanos <= MAX_SAMPLE_AGE_NANOS;
    }

    /** The matched latch survives newer telemetry; the service may retain it after a new capture. */
    public Capture getCapture(long solveGeneration) {
        return capture != null && capture.solveGeneration == solveGeneration ? capture : null;
    }

    /**
     * An ordered STATE read after DONE opens confirmation. If that cached read still carries the
     * previous latch, a later notification may supply the exact next capture without another read.
     */
    public Capture confirmCapture(long solveGeneration, GyroAttitudeProtocol.Snapshot terminal,
            boolean orderedPostDoneRead) {
        if (terminal == null) return null;
        if (orderedPostDoneRead) confirmationGeneration = solveGeneration;
        if (confirmationGeneration != solveGeneration) return null;
        Capture matched = getCapture(solveGeneration);
        if (matched == null || terminal == null || !terminal.isCaptureValid()
                || terminal.getCaptureSequence() != matched.sequence
                || terminal.getCaptureTick() != matched.tick) return null;
        double[] reported = terminal.getCaptureQuaternion();
        for (int i = 0; i < reported.length; i++) {
            if (reported[i] != matched.quaternion[i]) return null;
        }
        captureConfirmed = true;
        return matched;
    }

    /** Stop waiting after a canceled recipe without discarding an already matched latch. */
    public void cancelPendingCapture() {
        pending = null;
        confirmationGeneration = -1L;
    }

    public void clear() {
        invalidate();
        orderingKnown = false;
        tick = 0L;
        lastObservedReceiptNanos = -1L;
    }

    private void invalidate() {
        latest = null;
        capture = null;
        pending = null;
        confirmationGeneration = -1L;
        captureConfirmed = false;
    }

    private static long unsignedDifference(long newer, long older) {
        return (newer - older) & UINT32_MASK;
    }

    public static final class Sample {
        private final GyroAttitudeProtocol.Snapshot snapshot;
        private final long receiptElapsedRealtimeNanos;

        private Sample(GyroAttitudeProtocol.Snapshot snapshot, long receiptNanos) {
            this.snapshot = snapshot;
            this.receiptElapsedRealtimeNanos = receiptNanos;
        }

        public GyroAttitudeProtocol.Snapshot getSnapshot() { return snapshot; }
        public long getReceiptElapsedRealtimeNanos() { return receiptElapsedRealtimeNanos; }
    }

    public static final class Capture {
        private final long solveGeneration;
        private final long sequence;
        private final long tick;
        private final double[] quaternion;
        private final long elapsedRealtimeNanos;
        private final boolean preparation;

        private Capture(long solveGeneration, long sequence, long tick,
                double[] quaternion, long elapsedRealtimeNanos, boolean preparation) {
            this.solveGeneration = solveGeneration;
            this.sequence = sequence;
            this.tick = tick;
            this.quaternion = quaternion.clone();
            this.elapsedRealtimeNanos = elapsedRealtimeNanos;
            this.preparation = preparation;
        }

        public long getSolveGeneration() { return solveGeneration; }
        public long getSequence() { return sequence; }
        public long getTick() { return tick; }
        public double[] getQuaternion() { return quaternion.clone(); }
        public long getElapsedRealtimeNanos() { return elapsedRealtimeNanos; }
        public boolean isPreparation() { return preparation; }
    }

    private static final class PendingCapture {
        final long generation;
        final long sequence;
        final long tick;
        final long midpointNanos;
        final long uncertaintyNanos;

        PendingCapture(long generation, long sequence, long tick,
                long midpointNanos, long uncertaintyNanos) {
            this.generation = generation;
            this.sequence = sequence;
            this.tick = tick;
            this.midpointNanos = midpointNanos;
            this.uncertaintyNanos = uncertaintyNanos;
        }
    }
}
