package dev.omshutter.solver;

/**
 * Immutable optical-axis focal calibration from one accepted solve and its supplied focal.
 * The native solver measures the central ray derivative, including its fitted plane scale;
 * it converts native pixels to millimeters using its canonical 3.3 micrometer sensor pitch.
 * Edge field of view is unsuitable here because fitted radial distortion changes the rims.
 */
public final class PlanarFocalCalibration {
    private final double suppliedFocalLengthMm;
    private final double solvedFocalLengthMm;
    private final double scale;

    private PlanarFocalCalibration(double suppliedFocalLengthMm, double solvedFocalLengthMm) {
        this.suppliedFocalLengthMm = suppliedFocalLengthMm;
        this.solvedFocalLengthMm = solvedFocalLengthMm;
        this.scale = solvedFocalLengthMm / suppliedFocalLengthMm;
        requirePositiveFinite(scale, "focal calibration scale");
    }

    public static PlanarFocalCalibration of(double suppliedFocalLengthMm,
            double solvedFocalLengthMm) {
        requirePositiveFinite(suppliedFocalLengthMm, "supplied focal length");
        requirePositiveFinite(solvedFocalLengthMm, "solved focal length");
        return new PlanarFocalCalibration(suppliedFocalLengthMm, solvedFocalLengthMm);
    }

    public double getSuppliedFocalLengthMm() { return suppliedFocalLengthMm; }
    public double getSolvedFocalLengthMm() { return solvedFocalLengthMm; }
    public double getScale() { return scale; }

    /** Physical focal estimate for Q10-to-angle geometry; nominal camera metadata stays intact. */
    public double effectiveFocalLengthMm(double currentNominalFocalLengthMm) {
        requirePositiveFinite(currentNominalFocalLengthMm, "current nominal focal length");
        double effective = currentNominalFocalLengthMm * scale;
        requirePositiveFinite(effective, "effective focal length");
        return effective;
    }

    /**
     * Applies the focal ratio to continuous planar rates after atmospheric/Q10 geometry.
     * Roll is an angle and keeps its original rate. Exceeding the native lane limit throws
     * instead of allowing the wire converter to silently clamp a calibrated command.
     */
    public SiderealRateCorrection.BodyAngularVelocity apply(
            SiderealRateCorrection.BodyAngularVelocity body) {
        if (body == null) throw new IllegalArgumentException("body rate is unavailable");
        double x = body.getXRadiansPerSecond() * scale;
        double y = body.getYRadiansPerSecond() * scale;
        double z = body.getZRadiansPerSecond();
        double limit = SiderealRateCorrection.MAX_WIRE_COUNT
                * SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND;
        for (double lane : new double[]{x, y, z}) {
            if (!Double.isFinite(lane) || Math.abs(lane) > limit) {
                throw new IllegalArgumentException("calibrated rate exceeds the native lane limit");
            }
        }
        return SiderealRateCorrection.BodyAngularVelocity.of(x, y, z);
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }
}
