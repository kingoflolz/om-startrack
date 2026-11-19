package dev.omshutter.solver;

import java.util.Objects;

/** A validated celestial attitude used as the input to calculated sidereal correction. */
public final class CorrectionAttitude {
    private final double rightAscensionDeg;
    private final double declinationDeg;
    private final double rollDeg;

    private CorrectionAttitude(
            double rightAscensionDeg, double declinationDeg, double rollDeg) {
        this.rightAscensionDeg = rightAscensionDeg;
        this.declinationDeg = declinationDeg;
        this.rollDeg = rollDeg;
    }

    public static CorrectionAttitude of(
            double rightAscensionDeg, double declinationDeg, double rollDeg) {
        requireFinite(rightAscensionDeg, "right ascension");
        requireFinite(declinationDeg, "declination");
        requireFinite(rollDeg, "roll");
        if (declinationDeg < -90.0 || declinationDeg > 90.0) {
            throw new IllegalArgumentException("declination must be between -90 and 90 degrees");
        }
        return new CorrectionAttitude(
                normalizeUnsigned(rightAscensionDeg),
                declinationDeg,
                normalizeSigned(rollDeg));
    }

    public static CorrectionAttitude fromSolve(SolveResult result) {
        if (result == null) throw new IllegalArgumentException("plate solution is unavailable");
        return of(
                result.getRightAscensionDeg(),
                result.getDeclinationDeg(),
                result.getRollDeg());
    }

    public double getRightAscensionDeg() { return rightAscensionDeg; }
    public double getDeclinationDeg() { return declinationDeg; }
    public double getRollDeg() { return rollDeg; }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    private static double normalizeUnsigned(double value) {
        double normalized = value % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    private static double normalizeSigned(double value) {
        double normalized = normalizeUnsigned(value);
        return normalized >= 180.0 ? normalized - 360.0 : normalized;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CorrectionAttitude)) return false;
        CorrectionAttitude that = (CorrectionAttitude) other;
        return Double.compare(rightAscensionDeg, that.rightAscensionDeg) == 0
                && Double.compare(declinationDeg, that.declinationDeg) == 0
                && Double.compare(rollDeg, that.rollDeg) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(rightAscensionDeg, declinationDeg, rollDeg);
    }
}
