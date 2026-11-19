package dev.omshutter.plate;

/** Immutable solved attitude and the input indices used to verify it. */
public final class NativeSolution {
    private static final int METRIC_COUNT = 6;
    private static final int SOURCE_LIMIT = 256;

    private final double centerRaDeg;
    private final double centerDecDeg;
    private final double rollDeg;
    private final double horizontalFovDeg;
    private final double rmsResidualRad;
    private final double solvedFocalLengthMm;
    private final int[] patternSourceIndices;
    private final int[] matchedSourceIndices;

    private NativeSolution(
            double[] metrics,
            int[] patternSourceIndices,
            int[] matchedSourceIndices) {
        if (metrics.length != METRIC_COUNT) {
            throw new IllegalArgumentException("Native solution metric layout is invalid");
        }
        centerRaDeg = metrics[0];
        centerDecDeg = metrics[1];
        rollDeg = metrics[2];
        horizontalFovDeg = metrics[3];
        rmsResidualRad = metrics[4];
        solvedFocalLengthMm = metrics[5];
        if (!Double.isFinite(solvedFocalLengthMm) || solvedFocalLengthMm <= 0.0) {
            throw new IllegalArgumentException("Native central focal length is invalid");
        }
        if (patternSourceIndices.length != 4) {
            throw new IllegalArgumentException("Native solution must contain four pattern matches");
        }
        this.patternSourceIndices = patternSourceIndices.clone();
        this.matchedSourceIndices = matchedSourceIndices.clone();
        validateAssociationPartition(this.patternSourceIndices, this.matchedSourceIndices);
    }

    private static void validateAssociationPartition(
            int[] patternSourceIndices, int[] matchedSourceIndices) {
        boolean[] used = new boolean[SOURCE_LIMIT];
        for (int[] group : new int[][]{patternSourceIndices, matchedSourceIndices}) {
            for (int sourceIndex : group) {
                if (sourceIndex < 0 || sourceIndex >= used.length || used[sourceIndex]) {
                    throw new IllegalArgumentException(
                            "Native match indices are invalid, duplicated, or overlapping");
                }
                used[sourceIndex] = true;
            }
        }
    }

    static NativeSolution fromNative(
            double[] metrics,
            int[] patternSourceIndices,
            int[] matchedSourceIndices) {
        if (metrics.length == 0) {
            if (patternSourceIndices.length != 0 || matchedSourceIndices.length != 0) {
                throw new IllegalArgumentException("Empty native solution has match data");
            }
            return null;
        }
        return new NativeSolution(metrics, patternSourceIndices, matchedSourceIndices);
    }

    public double getCenterRaDeg() {
        return centerRaDeg;
    }

    public double getCenterDecDeg() {
        return centerDecDeg;
    }

    /** Position angle of image-up, in degrees east of celestial north. */
    public double getRollDeg() {
        return rollDeg;
    }

    public double getHorizontalFovDeg() {
        return horizontalFovDeg;
    }

    public double getRmsResidualArcsec() {
        return Math.toDegrees(rmsResidualRad) * 3600.0;
    }

    /** Central effective focal from the fitted ray derivative and canonical native pixel pitch. */
    public double getSolvedFocalLengthMm() {
        return solvedFocalLengthMm;
    }

    /** Exactly four input indices used by the accepted tetra pattern. */
    public int[] getPatternSourceIndices() {
        return patternSourceIndices.clone();
    }

    /** Input indices for independent checks, excluding the four pattern stars. */
    public int[] getMatchedSourceIndices() {
        return matchedSourceIndices.clone();
    }
}
