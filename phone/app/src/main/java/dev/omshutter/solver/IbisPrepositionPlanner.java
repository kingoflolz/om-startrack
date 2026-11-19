package dev.omshutter.solver;

/**
 * Plans a temporary CPU0 logical-position offset that centers a constant-rate exposure sweep.
 *
 * <p>The coordinates are the signed CPU0 targets at {@code 0x5d04/0x5d38/0x5d6c}, not the
 * filtered Handheld Assist Q10 display tuple. The v3 overlay applies negative gains to wire
 * yaw/pitch/roll. CPU0's positive downstream scales are {@code 1757/2^27} for planar axes and
 * {@code 4295/2^18} for roll, with yaw feeding logical X and pitch feeding logical Y. Thus a
 * positive wire lane needs a positive starting offset, opposite its subsequent negative sweep.
 * See {@code camera/firmware/IMAGE_STABILIZATION.md} and CPU0 {@code 0x40019b0c},
 * {@code 0x4001a219}, and {@code 0x4000bd28} for the arithmetic and coordinate path.</p>
 *
 * <p>This is an approximate body-only, constant-rate plan, not a measured actuator pose or a
 * promise of exposure duration. Stock profile limits, real motion, settling, and body/lens
 * allocation remain authoritative. The caller supplies the healthy acknowledged rate and
 * eligible focus-breathed FLact; it owns the half-press/settle/capture sequence.</p>
 */
public final class IbisPrepositionPlanner {
    // Actual still-capture geometry, independent of firmware detection or version selection.
    public static final int MAX_PLANAR_RADIUS_COUNTS = 2_546;
    public static final int MAX_ROLL_COUNTS = 5_325;

    public static final double USABLE_FRACTION = 0.98;
    private static final double AXIS_ROUNDING_MARGIN = 0.5;
    private static final double RADIAL_ROUNDING_MARGIN = Math.sqrt(0.5);
    private static final double COUPLED_BOX_INTERCEPT = 2_700.0;
    private static final double X_ROLL_SLOPE = 1_628.0 / 32_768.0;
    private static final double Y_ROLL_SLOPE = 1_571.0 / 32_768.0;
    private static final double CONTROL_HZ = 2_000.0;
    private static final int MAX_FLACT_UQ11_5 = 31_368;
    private static final Limits STILL_LIMITS = Limits.of(
            MAX_PLANAR_RADIUS_COUNTS, MAX_PLANAR_RADIUS_COUNTS, MAX_ROLL_COUNTS, true);

    /**
     * Active native bounds in logical target counts. The caller must qualify the profile,
     * builder and upstream limits for the intended capture; compiled maxima cannot replace
     * those current limits.
     */
    public static final class Limits {
        private static final Limits UNAVAILABLE = new Limits(0, 0, 0, false);
        private final int maximumX;
        private final int maximumY;
        private final int maximumRoll;
        private final boolean coupled;

        private Limits(int maximumX, int maximumY, int maximumRoll, boolean coupled) {
            this.maximumX = maximumX;
            this.maximumY = maximumY;
            this.maximumRoll = maximumRoll;
            this.coupled = coupled;
        }

        /** Unknown, disabled or unrepresentable bounds produce an unavailable limit set. */
        public static Limits of(int maximumX, int maximumY, int maximumRoll, boolean coupled) {
            if (!validBound(maximumX) || !validBound(maximumY) || !validBound(maximumRoll)) {
                return UNAVAILABLE;
            }
            return new Limits(maximumX, maximumY, maximumRoll, coupled);
        }

        public boolean isAvailable() { return maximumX > 0 && maximumY > 0 && maximumRoll > 0; }
        public int getMaximumX() { return maximumX; }
        public int getMaximumY() { return maximumY; }
        public int getMaximumRoll() { return maximumRoll; }
        public boolean isCoupled() { return coupled; }

        private static boolean validBound(int bound) {
            return bound > 0 && bound <= Short.MAX_VALUE;
        }
    }

    /** Immutable plan. Unavailable plans carry zero offsets and NaN duration estimates. */
    public static final class Plan {
        private static final Plan UNAVAILABLE = new Plan(0, 0, 0, Double.NaN, Double.NaN, false);

        private final int logicalX;
        private final int logicalY;
        private final int logicalRoll;
        private final double plannedSeconds;
        private final double maximumSeconds;
        private final boolean exposureClipped;

        private Plan(
                int logicalX,
                int logicalY,
                int logicalRoll,
                double plannedSeconds,
                double maximumSeconds,
                boolean exposureClipped) {
            this.logicalX = logicalX;
            this.logicalY = logicalY;
            this.logicalRoll = logicalRoll;
            this.plannedSeconds = plannedSeconds;
            this.maximumSeconds = maximumSeconds;
            this.exposureClipped = exposureClipped;
        }

        public boolean isAvailable() { return Double.isFinite(plannedSeconds); }
        public int getLogicalX() { return logicalX; }
        public int getLogicalY() { return logicalY; }
        public int getLogicalRoll() { return logicalRoll; }

        /** Duration used to center the offset; this does not change the camera exposure setting. */
        public double getPlannedSeconds() { return plannedSeconds; }

        /** Approximate duration of a complete sweep across the reserved envelope. */
        public double getMaximumSeconds() { return maximumSeconds; }

        /** True when the requested exposure exceeds this model's available sweep. */
        public boolean isExposureClipped() { return exposureClipped; }
    }

    private IbisPrepositionPlanner() {}

    /**
     * Centers the requested exposure in the still-capture envelope, or its maximum feasible
     * sweep if the requested exposure is longer.
     * Invalid focal/duration, absent rates, and an all-zero rate return an unavailable plan.
     * No telemetry-to-position conversion or generation/acknowledgement is performed here.
     * Focal is the camera's nominal FLact; acknowledged rates already include solved focal
     * calibration. Passing a calibrated focal here would apply the correction twice.
     */
    public static Plan plan(
            float focalLengthMm,
            SiderealRateCorrection acknowledgedRate,
            double exposureSeconds) {
        return plan(focalLengthMm, acknowledgedRate, exposureSeconds, STILL_LIMITS);
    }

    /**
     * Centers the native-rate trajectory inside 98% of the caller's active envelope.
     * Coupled mode intersects the planar circle and both roll-dependent boxes as well as
     * active axis limits. Independent mode uses only the active axis limits.
     * Nominal FLact and the already focal-calibrated acknowledged rate retain their units.
     */
    public static Plan plan(
            float focalLengthMm,
            SiderealRateCorrection acknowledgedRate,
            double exposureSeconds,
            Limits limits) {
        if (acknowledgedRate == null
                || limits == null || !limits.isAvailable()
                || !Float.isFinite(focalLengthMm)
                || focalLengthMm <= 0.0f
                || !Double.isFinite(exposureSeconds)
                || exposureSeconds <= 0.0) {
            return Plan.UNAVAILABLE;
        }

        double quantizedFocal = Math.floor((double) focalLengthMm * 32.0);
        if (quantizedFocal < 1.0 || quantizedFocal > MAX_FLACT_UQ11_5) {
            return Plan.UNAVAILABLE;
        }
        int focalUq11_5 = (int) quantizedFocal;
        int planarGainQ16 = -(23 * focalUq11_5
                + ((30_694 * focalUq11_5 + 32_768) >> 16));

        // Exact overlay coefficients followed by stock integration/output scales at the nominal
        // controller cadence. Keeping Q16 inputs avoids discarding sub-legacy-count tracking.
        double planarPerQ16 = Math.scalb(
                planarGainQ16 * CONTROL_HZ * 1_757.0, -32 - 27);
        double rollPerQ16 = Math.scalb(-192_253.0 * CONTROL_HZ * 4_295.0, -43 - 18);
        double speedX = acknowledgedRate.getYawQ16() * planarPerQ16;
        double speedY = acknowledgedRate.getPitchQ16() * planarPerQ16;
        double speedRoll = acknowledgedRate.getRollQ16() * rollPerQ16;

        // The centered segment lies on a ray through the origin. Every constraint is convex,
        // so its two endpoints bound the complete straight trajectory. Rounding the starting
        // point shifts both endpoints by at most half a count per axis (sqrt(0.5) radially).
        double x = Math.abs(speedX), y = Math.abs(speedY), r = Math.abs(speedRoll);
        double maximumHalfSeconds = Math.min(
                rayTime(USABLE_FRACTION * limits.maximumX - AXIS_ROUNDING_MARGIN, x),
                Math.min(rayTime(USABLE_FRACTION * limits.maximumY - AXIS_ROUNDING_MARGIN, y),
                        rayTime(USABLE_FRACTION * limits.maximumRoll - AXIS_ROUNDING_MARGIN, r)));
        if (limits.coupled) {
            maximumHalfSeconds = Math.min(maximumHalfSeconds,
                    rayTime(USABLE_FRACTION * MAX_PLANAR_RADIUS_COUNTS
                            - RADIAL_ROUNDING_MARGIN, Math.hypot(x, y)));
            // Continuous slopes are slightly tighter than the native floor-product boxes.
            maximumHalfSeconds = Math.min(maximumHalfSeconds,
                    rayTime(USABLE_FRACTION * COUPLED_BOX_INTERCEPT
                            - AXIS_ROUNDING_MARGIN * (1.0 + X_ROLL_SLOPE),
                            x + X_ROLL_SLOPE * r));
            maximumHalfSeconds = Math.min(maximumHalfSeconds,
                    rayTime(USABLE_FRACTION * COUPLED_BOX_INTERCEPT
                            - AXIS_ROUNDING_MARGIN * (1.0 + Y_ROLL_SLOPE),
                            y + Y_ROLL_SLOPE * r));
        }
        double maximumSeconds = 2.0 * maximumHalfSeconds;
        if (!Double.isFinite(maximumSeconds) || maximumSeconds <= 0.0) {
            return Plan.UNAVAILABLE;
        }

        double plannedSeconds = Math.min(exposureSeconds, maximumSeconds);
        double halfSeconds = plannedSeconds / 2.0;
        int startX = symmetricRound(-speedX * halfSeconds);
        int startY = symmetricRound(-speedY * halfSeconds);
        int startRoll = symmetricRound(-speedRoll * halfSeconds);
        if (!inside(limits, startX, startY, startRoll)
                || !inside(limits, startX + speedX * plannedSeconds,
                        startY + speedY * plannedSeconds,
                        startRoll + speedRoll * plannedSeconds)) {
            return Plan.UNAVAILABLE;
        }
        return new Plan(
                startX,
                startY,
                startRoll,
                plannedSeconds,
                maximumSeconds,
                exposureSeconds > maximumSeconds);
    }

    private static double rayTime(double radius, double speed) {
        return speed > 0.0 ? radius / speed : Double.POSITIVE_INFINITY;
    }

    private static boolean inside(Limits limits, double x, double y, double roll) {
        double epsilon = 1e-9;
        x = Math.abs(x);
        y = Math.abs(y);
        roll = Math.abs(roll);
        return x <= USABLE_FRACTION * limits.maximumX + epsilon
                && y <= USABLE_FRACTION * limits.maximumY + epsilon
                && roll <= USABLE_FRACTION * limits.maximumRoll + epsilon
                && (!limits.coupled
                    || Math.hypot(x, y) <= USABLE_FRACTION * MAX_PLANAR_RADIUS_COUNTS + epsilon
                    && x + X_ROLL_SLOPE * roll <= USABLE_FRACTION * COUPLED_BOX_INTERCEPT + epsilon
                    && y + Y_ROLL_SLOPE * roll <= USABLE_FRACTION * COUPLED_BOX_INTERCEPT + epsilon);
    }

    private static int symmetricRound(double value) {
        return (int) Math.copySign(Math.floor(Math.abs(value) + 0.5), value);
    }
}
