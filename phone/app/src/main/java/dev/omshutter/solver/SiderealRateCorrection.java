package dev.omshutter.solver;

import java.time.Instant;
import java.util.Objects;

/**
 * Calculated sidereal feed-forward command for the camera rate-patch ABI.
 *
 * <p>The plate solver and rate patch share the right-handed camera frame
 * {@code +X = image right}, {@code +Y = image down}, and {@code +Z = forward through the lens}.
 * A solve's roll is the position angle of image-up, measured east of celestial north. The
 * correction uses the documented body-to-wire mapping
 * {@code omegaBody = 1e-7 * (pitch, yaw, -roll) rad/s}.
 */
public final class SiderealRateCorrection {
    /** Nominal calculated Earth rotation rate used for sidereal feed-forward. */
    public static final double SIDEREAL_RATE_RAD_PER_SECOND = 7.2921150e-5;
    /** One signed rate-patch wire count in radians per second. */
    public static final double WIRE_UNIT_RAD_PER_SECOND = 1.0e-7;
    /** Number of fractional bits in the high-resolution wire-rate representation. */
    public static final int WIRE_Q16_FRACTION_BITS = 16;
    /** One legacy wire count expressed as a raw Q16.16 value. */
    public static final int WIRE_Q16_SCALE = 1 << WIRE_Q16_FRACTION_BITS;
    /** One raw Q16.16 rate unit in radians per second. */
    public static final double WIRE_Q16_UNIT_RAD_PER_SECOND =
            WIRE_UNIT_RAD_PER_SECOND / WIRE_Q16_SCALE;
    /** Independent useful limit of each rate-patch wire lane. */
    public static final int MAX_WIRE_COUNT = 2_917;
    /** Independent useful limit of each raw Q16.16 rate-patch wire lane. */
    public static final int MAX_WIRE_Q16 = MAX_WIRE_COUNT << WIRE_Q16_FRACTION_BITS;

    private final int yawQ16;
    private final int pitchQ16;
    private final int rollQ16;
    private final int yawCounts;
    private final int pitchCounts;
    private final int rollCounts;

    /** Continuous camera-body rate, retained until all physical adjustments are complete. */
    public static final class BodyAngularVelocity {
        private final double x;
        private final double y;
        private final double z;

        private BodyAngularVelocity(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public static BodyAngularVelocity of(double x, double y, double z) {
            requireFinite(x, "body X rate");
            requireFinite(y, "body Y rate");
            requireFinite(z, "body Z rate");
            return new BodyAngularVelocity(x, y, z);
        }

        public double getXRadiansPerSecond() { return x; }
        public double getYRadiansPerSecond() { return y; }
        public double getZRadiansPerSecond() { return z; }

        /** Returns a new right/down/forward array; callers cannot mutate this baseline. */
        public double[] toArray() { return new double[]{x, y, z}; }

        public SiderealRateCorrection toRateCorrection() {
            return fromBodyAngularVelocity(x, y, z);
        }
    }

    private SiderealRateCorrection(
            int yawQ16,
            int pitchQ16,
            int rollQ16,
            int yawCounts,
            int pitchCounts,
            int rollCounts) {
        this.yawQ16 = requireWireQ16(yawQ16, "yaw");
        this.pitchQ16 = requireWireQ16(pitchQ16, "pitch");
        this.rollQ16 = requireWireQ16(rollQ16, "roll");
        this.yawCounts = requireWireCount(yawCounts, "yaw");
        this.pitchCounts = requireWireCount(pitchCounts, "pitch");
        this.rollCounts = requireWireCount(rollCounts, "roll");
    }

    /** Calculates a command from one successful plate-solve result. */
    public static SiderealRateCorrection fromPlateSolve(SolveResult result) {
        return fromPlateSolve(result, 1.0);
    }

    /** Calculates and scales a command before the final symmetric wire quantization. */
    public static SiderealRateCorrection fromPlateSolve(SolveResult result, double factor) {
        return fromPlateSolve(result, factor, Instant.now());
    }

    /** Calculates a command at one injected UTC instant from the solved ICRS attitude. */
    public static SiderealRateCorrection fromPlateSolve(
            SolveResult result, double factor, Instant utc) {
        if (result == null) throw new IllegalArgumentException("plate-solve result is null");
        return fromEquatorialAttitude(
                result.getRightAscensionDeg(),
                result.getDeclinationDeg(),
                result.getRollDeg(),
                factor,
                utc);
    }

    /**
     * Calculates a sidereal command from the solved ICRS attitude.
     *
     * <p>The rotation axis is the mean Earth pole of the current date, expressed in ICRS.
     * Right ascension therefore matters as well as declination and image-up position angle.
     */
    public static SiderealRateCorrection fromEquatorialAttitude(
            double rightAscensionDeg,
            double declinationDeg,
            double imageUpPositionAngleDeg) {
        return fromEquatorialAttitude(
                rightAscensionDeg, declinationDeg, imageUpPositionAngleDeg, 1.0);
    }

    /** Calculates a strength-scaled sidereal command before final wire quantization. */
    public static SiderealRateCorrection fromEquatorialAttitude(
            double rightAscensionDeg,
            double declinationDeg,
            double imageUpPositionAngleDeg,
            double factor) {
        return fromEquatorialAttitude(
                rightAscensionDeg, declinationDeg, imageUpPositionAngleDeg, factor, Instant.now());
    }

    /**
     * Projects the injected date's mean Earth pole into the solved camera frame.
     *
     * <p>This shares the IAU 2006 P03 bias-precession model with manual gravity-derived roll.
     * UTC is used as TT; nutation and polar motion remain outside this mean-pole model.
     */
    public static SiderealRateCorrection fromEquatorialAttitude(
            double rightAscensionDeg,
            double declinationDeg,
            double imageUpPositionAngleDeg,
            double factor,
            Instant utc) {
        return bodyAngularVelocityFromEquatorialAttitude(
                rightAscensionDeg, declinationDeg, imageUpPositionAngleDeg, factor, utc)
                .toRateCorrection();
    }

    /** Full precision baseline for physical and solve-relative Q10 rate adjustments. */
    public static BodyAngularVelocity bodyAngularVelocityFromEquatorialAttitude(
            double rightAscensionDeg,
            double declinationDeg,
            double imageUpPositionAngleDeg,
            double factor,
            Instant utc) {
        requireFinite(rightAscensionDeg, "right ascension");
        requireFinite(declinationDeg, "declination");
        requireFinite(imageUpPositionAngleDeg, "image-up position angle");
        requireFinite(factor, "correction factor");
        if (factor < 0.0 || factor > 1.0) {
            throw new IllegalArgumentException("correction factor is outside 0..1");
        }
        if (declinationDeg < -90.0 || declinationDeg > 90.0) {
            throw new IllegalArgumentException("declination is outside -90..90 degrees");
        }
        if (utc == null) throw new IllegalArgumentException("UTC instant is null");

        double rightAscension = Math.toRadians(Math.IEEEremainder(rightAscensionDeg, 360.0));
        double declination = Math.toRadians(declinationDeg);
        double positionAngle = Math.toRadians(
                Math.IEEEremainder(imageUpPositionAngleDeg, 360.0));
        double sinRa = Math.sin(rightAscension);
        double cosRa = Math.cos(rightAscension);
        double sinDec = Math.sin(declination);
        double cosDec = Math.cos(declination);
        double[][] icrsToDate = GravityRollEstimator.icrsToMeanEquatorOfDateMatrix(
                utc.getEpochSecond(), utc.getNano());
        // P^T * (0,0,1): the third row of the ICRS -> mean-of-date coordinate matrix.
        double[] pole = icrsToDate[2];
        double poleEast = -pole[0] * sinRa + pole[1] * cosRa;
        double poleNorth = -pole[0] * sinDec * cosRa - pole[1] * sinDec * sinRa
                + pole[2] * cosDec;
        double poleForward = pole[0] * cosDec * cosRa + pole[1] * cosDec * sinRa
                + pole[2] * sinDec;

        // Same right/down/forward basis as CameraAttitudeEstimator:
        // right = -east*cos(PA) + north*sin(PA), down = -east*sin(PA) - north*cos(PA).
        double scale = factor * SIDEREAL_RATE_RAD_PER_SECOND;
        return BodyAngularVelocity.of(
                scale * (-poleEast * Math.cos(positionAngle)
                        + poleNorth * Math.sin(positionAngle)),
                scale * (-poleEast * Math.sin(positionAngle)
                        - poleNorth * Math.cos(positionAngle)),
                scale * poleForward);
    }

    /**
     * Converts a right-handed camera-body angular velocity to independently clamped wire lanes.
     */
    public static SiderealRateCorrection fromBodyAngularVelocity(
            double bodyXRadPerSecond,
            double bodyYRadPerSecond,
            double bodyZRadPerSecond) {
        requireFinite(bodyXRadPerSecond, "body X rate");
        requireFinite(bodyYRadPerSecond, "body Y rate");
        requireFinite(bodyZRadPerSecond, "body Z rate");
        double yawRate = bodyYRadPerSecond;
        double pitchRate = bodyXRadPerSecond;
        double rollRate = -bodyZRadPerSecond;
        return new SiderealRateCorrection(
                quantizeQ16AndClamp(yawRate),
                quantizeQ16AndClamp(pitchRate),
                quantizeQ16AndClamp(rollRate),
                quantizeLegacyAndClamp(yawRate),
                quantizeLegacyAndClamp(pitchRate),
                quantizeLegacyAndClamp(rollRate));
    }

    /**
     * Reconstructs the exact physical command represented by three admitted legacy wire lanes.
     *
     * <p>This is used after a v1 write succeeds so presentation and downstream estimates describe
     * the whole-count command that was actually transmitted, rather than unsent Q16 fractions.
     */
    public static SiderealRateCorrection fromLegacyWireCounts(
            int yawCounts,
            int pitchCounts,
            int rollCounts) {
        requireWireCount(yawCounts, "yaw");
        requireWireCount(pitchCounts, "pitch");
        requireWireCount(rollCounts, "roll");
        return new SiderealRateCorrection(
                yawCounts * WIRE_Q16_SCALE,
                pitchCounts * WIRE_Q16_SCALE,
                rollCounts * WIRE_Q16_SCALE,
                yawCounts,
                pitchCounts,
                rollCounts);
    }

    /** Reconstructs the canonical model represented by three admitted Q16.16 wire lanes. */
    public static SiderealRateCorrection fromQ16WireCounts(
            int yawQ16,
            int pitchQ16,
            int rollQ16) {
        requireWireQ16(yawQ16, "yaw");
        requireWireQ16(pitchQ16, "pitch");
        requireWireQ16(rollQ16, "roll");
        return new SiderealRateCorrection(
                yawQ16,
                pitchQ16,
                rollQ16,
                roundQ16ToLegacyCount(yawQ16),
                roundQ16ToLegacyCount(pitchQ16),
                roundQ16ToLegacyCount(rollQ16));
    }

    /** Legacy nearest-count view; exact half counts round away from zero. */
    public int getYawCounts() {
        return yawCounts;
    }

    /** Legacy nearest-count view; exact half counts round away from zero. */
    public int getPitchCounts() {
        return pitchCounts;
    }

    /** Legacy nearest-count view; exact half counts round away from zero. */
    public int getRollCounts() {
        return rollCounts;
    }

    /** Raw signed Q16.16 yaw-lane value, in units of one legacy wire count. */
    public int getYawQ16() {
        return yawQ16;
    }

    /** Raw signed Q16.16 pitch-lane value, in units of one legacy wire count. */
    public int getPitchQ16() {
        return pitchQ16;
    }

    /** Raw signed Q16.16 roll-lane value, in units of one legacy wire count. */
    public int getRollQ16() {
        return rollQ16;
    }

    /** High-resolution yaw wire-lane rate in radians per second. */
    public double getYawRadiansPerSecond() {
        return yawQ16 * WIRE_Q16_UNIT_RAD_PER_SECOND;
    }

    /** High-resolution pitch wire-lane rate in radians per second. */
    public double getPitchRadiansPerSecond() {
        return pitchQ16 * WIRE_Q16_UNIT_RAD_PER_SECOND;
    }

    /** High-resolution roll wire-lane rate in radians per second. */
    public double getRollRadiansPerSecond() {
        return rollQ16 * WIRE_Q16_UNIT_RAD_PER_SECOND;
    }

    private static int quantizeQ16AndClamp(double radiansPerSecond) {
        double limit = MAX_WIRE_COUNT * WIRE_UNIT_RAD_PER_SECOND;
        if (radiansPerSecond >= limit) return MAX_WIRE_Q16;
        if (radiansPerSecond <= -limit) return -MAX_WIRE_Q16;
        double wireQ16 = radiansPerSecond / WIRE_Q16_UNIT_RAD_PER_SECOND;
        // Symmetric nearest-integer quantization; exact half counts round away from zero.
        return wireQ16 >= 0.0
                ? (int) Math.floor(wireQ16 + 0.5)
                : (int) Math.ceil(wireQ16 - 0.5);
    }

    private static int quantizeLegacyAndClamp(double radiansPerSecond) {
        double limit = MAX_WIRE_COUNT * WIRE_UNIT_RAD_PER_SECOND;
        if (radiansPerSecond >= limit) return MAX_WIRE_COUNT;
        if (radiansPerSecond <= -limit) return -MAX_WIRE_COUNT;
        double wireCounts = radiansPerSecond / WIRE_UNIT_RAD_PER_SECOND;
        return wireCounts >= 0.0
                ? (int) Math.floor(wireCounts + 0.5)
                : (int) Math.ceil(wireCounts - 0.5);
    }

    private static int roundQ16ToLegacyCount(int value) {
        long magnitude = Math.abs((long) value);
        int rounded = (int) ((magnitude + WIRE_Q16_SCALE / 2L) / WIRE_Q16_SCALE);
        return value < 0 ? -rounded : rounded;
    }

    private static int requireWireQ16(int value, String field) {
        if (value < -MAX_WIRE_Q16 || value > MAX_WIRE_Q16) {
            throw new IllegalArgumentException(
                    field + " Q16.16 count is outside the rate-patch limit");
        }
        return value;
    }

    private static int requireWireCount(int value, String field) {
        if (value < -MAX_WIRE_COUNT || value > MAX_WIRE_COUNT) {
            throw new IllegalArgumentException(field + " count is outside the rate-patch limit");
        }
        return value;
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " is not finite");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SiderealRateCorrection)) return false;
        SiderealRateCorrection that = (SiderealRateCorrection) other;
        return yawQ16 == that.yawQ16
                && pitchQ16 == that.pitchQ16
                && rollQ16 == that.rollQ16
                && yawCounts == that.yawCounts
                && pitchCounts == that.pitchCounts
                && rollCounts == that.rollCounts;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                yawQ16, pitchQ16, rollQ16,
                yawCounts, pitchCounts, rollCounts);
    }

    @Override
    public String toString() {
        return "SiderealRateCorrection{yawQ16=" + yawQ16
                + ", pitchQ16=" + pitchQ16
                + ", rollQ16=" + rollQ16
                + ", yawCounts=" + yawCounts
                + ", pitchCounts=" + pitchCounts
                + ", rollCounts=" + rollCounts + "}";
    }
}
