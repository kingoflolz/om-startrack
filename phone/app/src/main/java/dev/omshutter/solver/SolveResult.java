package dev.omshutter.solver;

import java.util.Arrays;

/** Immutable successful plate solution. Angles exposed to the app are degrees. */
public final class SolveResult {
    private final double rightAscensionDeg;
    private final double declinationDeg;
    private final double rollDeg;
    private final double fovDeg;
    private final double residualArcsec;
    private final int[] patternSourceIndices;
    private final int[] matchedSourceIndices;
    private final PlanarFocalCalibration focalCalibration;

    public SolveResult(
            double rightAscensionDeg,
            double declinationDeg,
            double rollDeg,
            double fovDeg,
            double residualArcsec,
            int[] patternSourceIndices,
            int[] matchedSourceIndices) {
        this(rightAscensionDeg, declinationDeg, rollDeg, fovDeg, residualArcsec,
                patternSourceIndices, matchedSourceIndices, null);
    }

    public SolveResult(
            double rightAscensionDeg,
            double declinationDeg,
            double rollDeg,
            double fovDeg,
            double residualArcsec,
            int[] patternSourceIndices,
            int[] matchedSourceIndices,
            PlanarFocalCalibration focalCalibration) {
        if (!Double.isFinite(rightAscensionDeg)
                || !Double.isFinite(declinationDeg)
                || !Double.isFinite(rollDeg)
                || !Double.isFinite(fovDeg) || fovDeg <= 0.0
                || !Double.isFinite(residualArcsec) || residualArcsec < 0.0) {
            throw new IllegalArgumentException("invalid plate-solve result");
        }
        int[] patternSources = patternSourceIndices == null
                ? new int[0] : patternSourceIndices.clone();
        int[] sourceIndices = matchedSourceIndices == null
                ? new int[0] : matchedSourceIndices.clone();
        if (patternSources.length != 4) {
            throw new IllegalArgumentException("plate solution must have four pattern stars");
        }
        if (sourceIndices.length > SolveRequest.MAX_SOURCES - patternSources.length) {
            throw new IllegalArgumentException("too many matched stars");
        }
        boolean[] usedSourceIndices = new boolean[SolveRequest.MAX_SOURCES];
        validateDistinctSourceIndices(patternSources, usedSourceIndices, "pattern");
        validateDistinctSourceIndices(sourceIndices, usedSourceIndices, "validation");
        this.rightAscensionDeg = normalizeDegrees(rightAscensionDeg);
        this.declinationDeg = declinationDeg;
        this.rollDeg = normalizeSignedDegrees(rollDeg);
        this.fovDeg = fovDeg;
        this.residualArcsec = residualArcsec;
        this.patternSourceIndices = patternSources;
        this.matchedSourceIndices = sourceIndices;
        this.focalCalibration = focalCalibration;
    }

    public double getRightAscensionDeg() { return rightAscensionDeg; }
    public double getDeclinationDeg() { return declinationDeg; }
    public double getRollDeg() { return rollDeg; }
    /** Horizontal field of view. */
    public double getFovDeg() { return fovDeg; }
    public int getMatchedStars() { return matchedSourceIndices.length; }
    public double getResidualArcsec() { return residualArcsec; }
    /** Indices of the four published peaks which formed the tetra pattern. */
    public int[] getPatternSourceIndices() { return patternSourceIndices.clone(); }
    /** Indices of the published peaks used for independent verification. */
    public int[] getMatchedSourceIndices() { return matchedSourceIndices.clone(); }
    public boolean hasFocalCalibration() { return focalCalibration != null; }
    /** Null for manual/legacy results which did not retain native central-scale metadata. */
    public PlanarFocalCalibration getFocalCalibration() { return focalCalibration; }
    /** Focal supplied to this exact solve, or NaN when calibration metadata is unavailable. */
    public double getSuppliedFocalLengthMm() {
        return focalCalibration == null ? Double.NaN : focalCalibration.getSuppliedFocalLengthMm();
    }
    /** Fitted optical-axis effective focal, or NaN when unavailable. Not inferred from edge FOV. */
    public double getSolvedFocalLengthMm() {
        return focalCalibration == null ? Double.NaN : focalCalibration.getSolvedFocalLengthMm();
    }

    private static void validateDistinctSourceIndices(
            int[] sourceIndices, boolean[] usedSourceIndices, String label) {
        for (int sourceIndex : sourceIndices) {
            if (sourceIndex < 0
                    || sourceIndex >= usedSourceIndices.length
                    || usedSourceIndices[sourceIndex]) {
                throw new IllegalArgumentException(
                        label + " STAR source indices are invalid or overlap");
            }
            usedSourceIndices[sourceIndex] = true;
        }
    }

    private static double normalizeDegrees(double value) {
        double normalized = value % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    private static double normalizeSignedDegrees(double value) {
        double normalized = normalizeDegrees(value);
        return normalized >= 180.0 ? normalized - 360.0 : normalized;
    }

    @Override
    public String toString() {
        return "SolveResult{ra=" + rightAscensionDeg
                + ", dec=" + declinationDeg
                + ", roll=" + rollDeg
                + ", fov=" + fovDeg
                + ", matched=" + matchedSourceIndices.length
                + ", residualArcsec=" + residualArcsec
                + ", patternSources=" + Arrays.toString(patternSourceIndices)
                + ", validationSources=" + Arrays.toString(matchedSourceIndices) + "}";
    }
}
