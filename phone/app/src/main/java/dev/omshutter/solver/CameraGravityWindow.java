package dev.omshutter.solver;

import java.util.ArrayDeque;

/** Bounded sliding estimator for calibrated physical-down accelerometer samples. */
public final class CameraGravityWindow {
    public static final long MIN_WINDOW_NANOS = 500_000_000L;
    public static final long MAX_SAMPLE_AGE_NANOS = 2_000_000_000L;
    public static final long MAX_SAMPLE_GAP_NANOS = 250_000_000L;
    public static final int MIN_SAMPLES = 5;
    public static final double MIN_MEAN_NORM_MILLIG = 900.0;
    public static final double MAX_MEAN_NORM_MILLIG = 1_100.0;
    public static final double MAX_RMS_RESIDUAL_MILLIG = 5.0;
    public static final int MAX_SAMPLES = 4_096;

    public enum State {
        QUALIFIED,
        UNAVAILABLE,
        UNSTABLE
    }

    /** Immutable calculation over the samples retained when {@link #estimate(long)} was called. */
    public static final class Estimate {
        private final State state;
        private final int sampleCount;
        private final long oldestElapsedRealtimeNanos;
        private final long newestElapsedRealtimeNanos;
        private final double downXMillig;
        private final double downYMillig;
        private final double downZMillig;
        private final double meanNormMillig;
        private final double rmsResidualMillig;

        private Estimate(
                State state,
                int sampleCount,
                long oldestElapsedRealtimeNanos,
                long newestElapsedRealtimeNanos,
                double downXMillig,
                double downYMillig,
                double downZMillig,
                double meanNormMillig,
                double rmsResidualMillig) {
            this.state = state;
            this.sampleCount = sampleCount;
            this.oldestElapsedRealtimeNanos = oldestElapsedRealtimeNanos;
            this.newestElapsedRealtimeNanos = newestElapsedRealtimeNanos;
            this.downXMillig = downXMillig;
            this.downYMillig = downYMillig;
            this.downZMillig = downZMillig;
            this.meanNormMillig = meanNormMillig;
            this.rmsResidualMillig = rmsResidualMillig;
        }

        private static Estimate unavailable() {
            return new Estimate(
                    State.UNAVAILABLE, 0, -1L, -1L,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        public State getState() {
            return state;
        }

        public boolean isQualified() {
            return state == State.QUALIFIED;
        }

        public int getSampleCount() {
            return sampleCount;
        }

        public long getOldestElapsedRealtimeNanos() {
            return oldestElapsedRealtimeNanos;
        }

        public long getNewestElapsedRealtimeNanos() {
            return newestElapsedRealtimeNanos;
        }

        public double getDownXMillig() {
            return downXMillig;
        }

        public double getDownYMillig() {
            return downYMillig;
        }

        public double getDownZMillig() {
            return downZMillig;
        }

        public double getMeanNormMillig() {
            return meanNormMillig;
        }

        public double getRmsResidualMillig() {
            return rmsResidualMillig;
        }
    }

    private static final class Sample {
        final int x;
        final int y;
        final int z;
        final long elapsedRealtimeNanos;

        Sample(int x, int y, int z, long elapsedRealtimeNanos) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.elapsedRealtimeNanos = elapsedRealtimeNanos;
        }
    }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    /** Adds one calibrated camera-frame physical-down sample, in integer milligravity lanes. */
    public synchronized void addSample(
            int downXMillig,
            int downYMillig,
            int downZMillig,
            long receivedElapsedRealtimeNanos) {
        if (receivedElapsedRealtimeNanos < 0L) {
            throw new IllegalArgumentException("gravity timestamp is negative");
        }
        Sample newest = samples.peekLast();
        if (newest != null && receivedElapsedRealtimeNanos < newest.elapsedRealtimeNanos) {
            throw new IllegalArgumentException("gravity timestamps are not monotonic");
        }

        samples.addLast(new Sample(
                downXMillig, downYMillig, downZMillig, receivedElapsedRealtimeNanos));
        long earliestRetained = receivedElapsedRealtimeNanos - MAX_SAMPLE_AGE_NANOS;
        while (!samples.isEmpty()
                && samples.peekFirst().elapsedRealtimeNanos < earliestRetained) {
            samples.removeFirst();
        }
        while (samples.size() > MAX_SAMPLES) samples.removeFirst();
    }

    public synchronized void clear() {
        samples.clear();
    }

    public synchronized int size() {
        return samples.size();
    }

    /** Computes a quality-classified mean without retaining a reference to mutable window state. */
    public synchronized Estimate estimate(long elapsedRealtimeNanos) {
        if (elapsedRealtimeNanos < 0L) {
            throw new IllegalArgumentException("elapsed realtime is negative");
        }
        if (samples.isEmpty()) return Estimate.unavailable();

        long earliestRetained = elapsedRealtimeNanos - MAX_SAMPLE_AGE_NANOS;
        while (!samples.isEmpty()
                && samples.peekFirst().elapsedRealtimeNanos < earliestRetained) {
            samples.removeFirst();
        }
        if (samples.isEmpty()) return Estimate.unavailable();

        Sample oldest = samples.peekFirst();
        Sample newest = samples.peekLast();
        if (elapsedRealtimeNanos < newest.elapsedRealtimeNanos) {
            throw new IllegalArgumentException("elapsed realtime precedes gravity timestamp");
        }
        if (elapsedRealtimeNanos - newest.elapsedRealtimeNanos > MAX_SAMPLE_AGE_NANOS
                || newest.elapsedRealtimeNanos - oldest.elapsedRealtimeNanos < MIN_WINDOW_NANOS
                || samples.size() < MIN_SAMPLES
                || hasExcessiveGap()) {
            return new Estimate(
                    State.UNAVAILABLE,
                    samples.size(),
                    oldest.elapsedRealtimeNanos,
                    newest.elapsedRealtimeNanos,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        double sumX = 0.0;
        double sumY = 0.0;
        double sumZ = 0.0;
        for (Sample sample : samples) {
            sumX += sample.x;
            sumY += sample.y;
            sumZ += sample.z;
        }
        double count = samples.size();
        double meanX = sumX / count;
        double meanY = sumY / count;
        double meanZ = sumZ / count;
        double meanNorm = Math.sqrt(meanX * meanX + meanY * meanY + meanZ * meanZ);

        double sumSquaredResidual = 0.0;
        for (Sample sample : samples) {
            double dx = sample.x - meanX;
            double dy = sample.y - meanY;
            double dz = sample.z - meanZ;
            sumSquaredResidual += dx * dx + dy * dy + dz * dz;
        }
        double rmsResidual = Math.sqrt(sumSquaredResidual / count);
        State state = meanNorm >= MIN_MEAN_NORM_MILLIG
                        && meanNorm <= MAX_MEAN_NORM_MILLIG
                        && rmsResidual <= MAX_RMS_RESIDUAL_MILLIG
                ? State.QUALIFIED
                : State.UNSTABLE;
        return new Estimate(
                state,
                samples.size(),
                oldest.elapsedRealtimeNanos,
                newest.elapsedRealtimeNanos,
                meanX,
                meanY,
                meanZ,
                meanNorm,
                rmsResidual);
    }

    private boolean hasExcessiveGap() {
        long previous = -1L;
        for (Sample sample : samples) {
            if (previous >= 0L
                    && sample.elapsedRealtimeNanos - previous
                        > MAX_SAMPLE_GAP_NANOS) {
                return true;
            }
            previous = sample.elapsedRealtimeNanos;
        }
        return false;
    }
}
