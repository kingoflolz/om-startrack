package dev.omshutter.plate;

/** Structured result returned by the Rust solver. */
public final class NativeSolveResult {
    public static final int STATUS_SOLVED = 0;
    public static final int STATUS_NO_MATCH = 1;
    public static final int STATUS_AMBIGUOUS = 2;

    public static final int NO_MATCH_NONE = 0;
    public static final int NO_MATCH_INSUFFICIENT_OBSERVATIONS = 1;
    public static final int NO_MATCH_SEARCH_EXHAUSTED = 2;
    public static final int NO_MATCH_REJECTED_BY_VERIFICATION = 3;
    public static final int NO_MATCH_BUDGET_EXHAUSTED = 4;
    public static final int NO_MATCH_DEADLINE_EXCEEDED = 5;

    private final int status;
    private final int noMatchReason;
    private final String selectedProfileId;
    private final NativeSolution solution;
    private final double[] ambiguityChanceProbabilities;

    // Called directly by JNI. Keep this descriptor synchronized with
    // RESULT_CONSTRUCTOR in tetra3-android/src/jni_bridge.rs.
    NativeSolveResult(
            int status,
            int noMatchReason,
            String selectedProfileId,
            double[] bestMetrics,
            int[] bestPatternSourceIndices,
            int[] bestMatchedSourceIndices,
            double[] ambiguityChanceProbabilities) {
        if (status < STATUS_SOLVED || status > STATUS_AMBIGUOUS) {
            throw new IllegalArgumentException("Unknown native solve status");
        }
        if (noMatchReason < NO_MATCH_NONE || noMatchReason > NO_MATCH_DEADLINE_EXCEEDED) {
            throw new IllegalArgumentException("Unknown native no-match reason");
        }
        this.status = status;
        this.noMatchReason = noMatchReason;
        this.selectedProfileId = selectedProfileId.isEmpty() ? null : selectedProfileId;
        this.solution = NativeSolution.fromNative(
                bestMetrics,
                bestPatternSourceIndices,
                bestMatchedSourceIndices);
        this.ambiguityChanceProbabilities = ambiguityChanceProbabilities.clone();

        if (status == STATUS_SOLVED
                && (solution == null || this.ambiguityChanceProbabilities.length != 0)) {
            throw new IllegalArgumentException("Solved native result shape is invalid");
        }
        if (status == STATUS_NO_MATCH
                && (solution != null || this.ambiguityChanceProbabilities.length != 0)) {
            throw new IllegalArgumentException("No-match native result contains a solution");
        }
        if (status == STATUS_AMBIGUOUS
                && (solution != null || !validAmbiguity(this.ambiguityChanceProbabilities))) {
            throw new IllegalArgumentException("Ambiguous native result shape is invalid");
        }
        if ((status == STATUS_NO_MATCH) != (noMatchReason != NO_MATCH_NONE)) {
            throw new IllegalArgumentException("Native status/no-match reason disagree");
        }
    }

    public int getStatus() { return status; }
    public int getNoMatchReason() { return noMatchReason; }
    public boolean usedRectilinearFallback() {
        return selectedProfileId == null;
    }
    public String getSelectedProfileId() { return selectedProfileId; }
    public NativeSolution getSolution() { return solution; }
    public double[] getAmbiguityChanceProbabilities() {
        return ambiguityChanceProbabilities.clone();
    }

    private static boolean validAmbiguity(double[] probabilities) {
        if (probabilities.length != 2) return false;
        for (double probability : probabilities) {
            if (!Double.isFinite(probability) || probability < 0.0 || probability > 1.0) {
                return false;
            }
        }
        return true;
    }
}
