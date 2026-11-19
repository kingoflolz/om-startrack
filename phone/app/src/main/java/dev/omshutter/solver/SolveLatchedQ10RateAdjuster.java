package dev.omshutter.solver;

import dev.omshutter.protocol.StarTelemetry;
import java.time.Instant;

/**
 * Pure solve-anchored sidereal-rate adjustment from two qualified IBIS Q10 observations.
 *
 * <p>The public Q10 lanes are correction/display accumulators, not measured Hall position. This
 * helper therefore uses only the planar change since the solve as a provisional local optical
 * proxy. The recovered planar producer limit gives {@code 0.6 / 1024 mm} per Q10 count. With
 * mapped image-right and image-down displacements {@code tx} and {@code ty}, the local gnomonic
 * coordinates are {@code u = tx / focalLength} and {@code v = ty / focalLength}.</p>
 *
 * <p>The base continuous wire-count vector {@code [Y, P, R]} projects the current date's mean
 * Earth pole into the solved ICRS attitude, using the same model as the unadjusted rate. The
 * solve-anchored local feed-forward is:</p>
 *
 * <pre>
 * Y' = Y(1 + u^2) - Puv + Rv
 * P' = P(1 + v^2) - Yuv + Ru
 * R' = R - (Pu + Yv) / 2
 * </pre>
 *
 * <p>Raw roll remains qualification and conservative lane-gate input, but does not change the
 * provisional rate because no roll-to-optical cross-coupling calibration has been established.
 * This class is stateless: it contains no feedback, filtering, history, slew limit, or scheduler.
 * Conservatively, both observations must keep every lane within {@code abs(q) < 960}, and the
 * mapped planar radius must satisfy {@code hypot(u, v) <= 0.02}.</p>
 *
 * <p>The STAR raw-IBIS contract does not prove the absolute signs or camera-frame assignment
 * of its producer-order planar lanes. {@link #DEFAULT_AXIS_MAPPING} is the explicit production
 * convention that treats raw X as image-right and raw Y as image-down. A bench-qualified signed
 * permutation can be supplied without changing the geometry.</p>
 */
public final class SolveLatchedQ10RateAdjuster {
    public static final double PLANAR_MM_PER_Q10_COUNT =
            0.6 / StarTelemetry.IBIS_Q10_FULL_SCALE;
    public static final double SIDEREAL_WIRE_COUNTS =
            SiderealRateCorrection.SIDEREAL_RATE_RAD_PER_SECOND
                    / SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND;
    public static final int Q10_LANE_ABS_LIMIT_EXCLUSIVE = 960;
    public static final double MAX_GNOMONIC_RADIUS = 0.02;

    /** Direct producer-order interpretation used by the no-calibration overload. */
    public static final AxisMapping DEFAULT_AXIS_MAPPING = new AxisMapping(
            PlanarLane.RAW_X,
            1,
            PlanarLane.RAW_Y,
            1);

    /** The two producer-order planar lanes available for a signed permutation. */
    public enum PlanarLane {
        RAW_X,
        RAW_Y
    }

    /**
     * Discrete producer-lane calibration into image-right and image-down.
     *
     * <p>The planar lanes must be distinct and every sign must be exactly {@code -1} or
     * {@code +1}; scale calibration deliberately remains fixed to the recovered Q10 producer
     * limits.</p>
     */
    public static final class AxisMapping {
        private final PlanarLane imageRightLane;
        private final int imageRightSign;
        private final PlanarLane imageDownLane;
        private final int imageDownSign;

        public AxisMapping(
                PlanarLane imageRightLane,
                int imageRightSign,
                PlanarLane imageDownLane,
                int imageDownSign) {
            if (imageRightLane == null || imageDownLane == null) {
                throw new IllegalArgumentException("planar Q10 lane mapping is null");
            }
            if (imageRightLane == imageDownLane) {
                throw new IllegalArgumentException("planar Q10 lanes must form a permutation");
            }
            this.imageRightLane = imageRightLane;
            this.imageRightSign = requireSign(imageRightSign, "image-right");
            this.imageDownLane = imageDownLane;
            this.imageDownSign = requireSign(imageDownSign, "image-down");
        }

        public PlanarLane getImageRightLane() { return imageRightLane; }
        public int getImageRightSign() { return imageRightSign; }
        public PlanarLane getImageDownLane() { return imageDownLane; }
        public int getImageDownSign() { return imageDownSign; }

        private static int requireSign(int sign, String label) {
            if (sign != -1 && sign != 1) {
                throw new IllegalArgumentException(label + " Q10 sign must be -1 or +1");
            }
            return sign;
        }
    }

    public enum Status {
        APPLIED,
        UNAVAILABLE
    }

    /** Immutable adjustment result; unavailable results intentionally contain no fallback rate. */
    public static final class Result {
        private static final Result UNAVAILABLE =
                new Result(Status.UNAVAILABLE, null, Double.NaN, Double.NaN);

        private final Status status;
        private final SiderealRateCorrection.BodyAngularVelocity bodyAngularVelocity;
        private final SiderealRateCorrection correction;
        private final double imageRightGnomonic;
        private final double imageDownGnomonic;

        private Result(
                Status status,
                SiderealRateCorrection.BodyAngularVelocity bodyAngularVelocity,
                double imageRightGnomonic,
                double imageDownGnomonic) {
            this.status = status;
            this.bodyAngularVelocity = bodyAngularVelocity;
            this.correction = bodyAngularVelocity == null ? null : bodyAngularVelocity.toRateCorrection();
            this.imageRightGnomonic = imageRightGnomonic;
            this.imageDownGnomonic = imageDownGnomonic;
        }

        public Status getStatus() { return status; }
        public boolean isApplied() { return status == Status.APPLIED; }
        public SiderealRateCorrection getCorrection() { return correction; }

        /** Continuous effective rate before wire rounding; null when unavailable. */
        public SiderealRateCorrection.BodyAngularVelocity getBodyAngularVelocity() {
            return bodyAngularVelocity;
        }

        /** Qualified solve-relative displacement divided by focal length; NaN if unavailable. */
        public double getImageRightGnomonic() { return imageRightGnomonic; }
        public double getImageDownGnomonic() { return imageDownGnomonic; }

        private static Result applied(
                SiderealRateCorrection.BodyAngularVelocity body,
                double imageRightGnomonic,
                double imageDownGnomonic) {
            return new Result(Status.APPLIED, body, imageRightGnomonic, imageDownGnomonic);
        }

        private static Result unavailable() { return UNAVAILABLE; }
    }

    private SolveLatchedQ10RateAdjuster() {}

    /** Applies the explicit direct producer-order {@link #DEFAULT_AXIS_MAPPING}. */
    public static Result adjust(
            CorrectionAttitude solveAttitude,
            double factor,
            StarTelemetry.IbisSample solveLatchedQ10,
            StarTelemetry.IbisSample latestQ10,
            double focalLengthMm) {
        return adjust(
                solveAttitude,
                factor,
                solveLatchedQ10,
                latestQ10,
                focalLengthMm,
                DEFAULT_AXIS_MAPPING);
    }

    /**
     * Returns an adjusted rate only when the solve-latched and latest observations are qualified
     * and inside the conservative lane/radial gates and all scalar inputs are finite and in range.
     *
     * <p>Linux sequence values and the sticky observed-change flag are intentionally ignored: the
     * protocol defines neither as a source timestamp or freshness certificate.</p>
     */
    public static Result adjust(
            CorrectionAttitude solveAttitude,
            double factor,
            StarTelemetry.IbisSample solveLatchedQ10,
            StarTelemetry.IbisSample latestQ10,
            double focalLengthMm,
            AxisMapping mapping) {
        return adjust(solveAttitude, factor, solveLatchedQ10, latestQ10, focalLengthMm,
                mapping, Instant.now());
    }

    /** Uses one injected UTC instant for the zero-delta and adjusted rate baselines. */
    public static Result adjust(
            CorrectionAttitude solveAttitude,
            double factor,
            StarTelemetry.IbisSample solveLatchedQ10,
            StarTelemetry.IbisSample latestQ10,
            double focalLengthMm,
            AxisMapping mapping,
            Instant utc) {
        if (solveAttitude == null
                || mapping == null
                || utc == null
                || !Double.isFinite(factor)
                || factor < 0.0
                || factor > 1.0
                || !Double.isFinite(focalLengthMm)
                || focalLengthMm <= 0.0
                || !isUsableQ10(solveLatchedQ10)
                || !isUsableQ10(latestQ10)) {
            return Result.unavailable();
        }
        SiderealRateCorrection.BodyAngularVelocity body =
                SiderealRateCorrection.bodyAngularVelocityFromEquatorialAttitude(
                        solveAttitude.getRightAscensionDeg(),
                        solveAttitude.getDeclinationDeg(),
                        solveAttitude.getRollDeg(),
                        factor,
                        utc);
        return adjustBaseline(body, solveLatchedQ10, latestQ10, focalLengthMm, mapping);
    }

    /**
     * Adjusts one retained continuous baseline, including any dated pole/refraction correction.
     * No additional strength scaling or intermediate wire quantization is applied.
     */
    public static Result adjustBaseline(
            SiderealRateCorrection.BodyAngularVelocity body,
            StarTelemetry.IbisSample solveLatchedQ10,
            StarTelemetry.IbisSample latestQ10,
            double focalLengthMm,
            AxisMapping mapping) {
        if (body == null
                || mapping == null
                || !Double.isFinite(focalLengthMm)
                || focalLengthMm <= 0.0
                || !isUsableQ10(solveLatchedQ10)
                || !isUsableQ10(latestQ10)) {
            return Result.unavailable();
        }

        int deltaRawX = latestQ10.getRawX() - solveLatchedQ10.getRawX();
        int deltaRawY = latestQ10.getRawY() - solveLatchedQ10.getRawY();
        if (deltaRawX == 0 && deltaRawY == 0) {
            return Result.applied(body, 0.0, 0.0);
        }
        int imageRightQ10 = mapping.imageRightSign
                * laneDelta(mapping.imageRightLane, deltaRawX, deltaRawY);
        int imageDownQ10 = mapping.imageDownSign
                * laneDelta(mapping.imageDownLane, deltaRawX, deltaRawY);

        double u = imageRightQ10 * PLANAR_MM_PER_Q10_COUNT / focalLengthMm;
        double v = imageDownQ10 * PLANAR_MM_PER_Q10_COUNT / focalLengthMm;
        if (!Double.isFinite(u)
                || !Double.isFinite(v)
                || Math.hypot(u, v) > MAX_GNOMONIC_RADIUS) {
            return Result.unavailable();
        }
        double yaw = body.getYRadiansPerSecond();
        double pitch = body.getXRadiansPerSecond();
        double roll = -body.getZRadiansPerSecond();
        double uv = u * v;
        double adjustedYaw = yaw * (1.0 + u * u) - pitch * uv + roll * v;
        double adjustedPitch = pitch * (1.0 + v * v) - yaw * uv + roll * u;
        double adjustedRoll = roll - 0.5 * (pitch * u + yaw * v);
        if (!Double.isFinite(adjustedYaw)
                || !Double.isFinite(adjustedPitch)
                || !Double.isFinite(adjustedRoll)) {
            return Result.unavailable();
        }

        return Result.applied(SiderealRateCorrection.BodyAngularVelocity.of(
                adjustedPitch,
                adjustedYaw,
                -adjustedRoll), u, v);
    }

    private static boolean isUsableQ10(StarTelemetry.IbisSample sample) {
        return sample != null
                && sample.isSourceMapped()
                && sample.isMirrorEnabled()
                && sample.isDoubleReadStable()
                && isInsideLaneGuard(sample.getRawX())
                && isInsideLaneGuard(sample.getRawY())
                && isInsideLaneGuard(sample.getRawRoll());
    }

    private static boolean isInsideLaneGuard(int q10) {
        return Math.abs(q10) < Q10_LANE_ABS_LIMIT_EXCLUSIVE;
    }

    private static int laneDelta(PlanarLane lane, int deltaRawX, int deltaRawY) {
        return lane == PlanarLane.RAW_X ? deltaRawX : deltaRawY;
    }
}
