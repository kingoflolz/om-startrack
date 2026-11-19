package dev.omshutter.solver;

/** Immutable state published while one validated STAR generation is solved. */
public final class SolveSnapshot {
    public static final long DURATION_UNAVAILABLE_NANOS = -1L;

    public enum State {
        IDLE,
        QUEUED,
        SOLVING,
        SOLVED,
        NO_MATCH,
        MISSING_METADATA,
        ERROR,
        CLOSED
    }

    private final State state;
    private final long generation;
    private final SolveResult result;
    private final String detail;
    private final boolean lensMetadataMissing;
    private final boolean distortionFallback;
    private final long solveDurationNanos;

    private SolveSnapshot(
            State state,
            long generation,
            SolveResult result,
            String detail,
            boolean lensMetadataMissing,
            boolean distortionFallback,
            long solveDurationNanos) {
        this.state = state;
        this.generation = generation;
        this.result = result;
        this.detail = detail == null ? "" : detail;
        this.lensMetadataMissing = lensMetadataMissing;
        this.distortionFallback = distortionFallback;
        this.solveDurationNanos = solveDurationNanos;
    }

    public static SolveSnapshot idle() {
        return new SolveSnapshot(
                State.IDLE, -1, null, "", false, false, DURATION_UNAVAILABLE_NANOS);
    }

    public static SolveSnapshot of(
            State state,
            long generation,
            SolveResult result,
            String detail,
            boolean lensMetadataMissing,
            boolean distortionFallback) {
        if (state == null) throw new IllegalArgumentException("solve state is null");
        if (state == State.SOLVED && result == null) {
            throw new IllegalArgumentException("SOLVED state requires a result");
        }
        if (state != State.SOLVED && result != null) {
            throw new IllegalArgumentException("only SOLVED state may carry a result");
        }
        return new SolveSnapshot(
                state,
                generation,
                result,
                detail,
                lensMetadataMissing,
                distortionFallback,
                DURATION_UNAVAILABLE_NANOS);
    }

    /** Creates a worker-terminal solve snapshot with its exact monotonic execution duration. */
    public static SolveSnapshot terminal(
            State state,
            long generation,
            SolveResult result,
            String detail,
            boolean lensMetadataMissing,
            boolean distortionFallback,
            long solveDurationNanos) {
        if (state != State.SOLVED && state != State.NO_MATCH && state != State.ERROR) {
            throw new IllegalArgumentException("solve duration requires a terminal worker state");
        }
        if (solveDurationNanos < 0L) {
            throw new IllegalArgumentException("solve duration is negative");
        }
        if (state == State.SOLVED && result == null) {
            throw new IllegalArgumentException("SOLVED state requires a result");
        }
        if (state != State.SOLVED && result != null) {
            throw new IllegalArgumentException("only SOLVED state may carry a result");
        }
        return new SolveSnapshot(
                state,
                generation,
                result,
                detail,
                lensMetadataMissing,
                distortionFallback,
                solveDurationNanos);
    }

    public State getState() { return state; }
    public long getGeneration() { return generation; }
    public SolveResult getResult() { return result; }
    public String getDetail() { return detail; }
    public boolean isLensMetadataMissing() { return lensMetadataMissing; }
    public boolean isDistortionFallback() { return distortionFallback; }
    public boolean hasSolveDuration() { return solveDurationNanos >= 0L; }
    public long getSolveDurationNanos() { return solveDurationNanos; }
}
