package dev.omshutter.solver;

import dev.omshutter.protocol.StarTelemetry;

/**
 * Pure, side-effect-free estimator for the correction time available from a centered IBIS stage
 * and from the latest qualified Handheld Assist display-space tuple.
 *
 * <p>The public Q10 tuple is not physical stage position.  A {@link Calibration} must therefore
 * explicitly opt into a remaining-time proxy; keeping that calibration absent makes both product
 * values unavailable instead of silently treating display-space telemetry as millimetres.</p>
 */
public final class TrackingExposureEstimator {
    private static final double DEGREES_PER_RADIAN = 180.0 / Math.PI;
    private static final double FOCAL_QUANTIZATION_PER_MM = 32.0;
    private static final double Q10_FULL_SCALE = StarTelemetry.IBIS_Q10_FULL_SCALE;

    /*
     * Conservative simultaneous stock envelope recovered from the normal coupled limiter.
     * These are deliberately smaller than the independent profile maxima.  The Q10 bridge is
     * only a correction-space equivalent: it is not Hall feedback or a guaranteed stage pose.
     */
    private static final Calibration RECOVERED_Q10_PROXY_CALIBRATION = new Calibration(
            1_642.0 / 3_000.0,
            3_276.0 / 8_192.0,
            new RemainingProxy(1_800, 5_325, 3_000.0, 8_192.0));

    /**
     * Physical centered-stage envelope plus an explicit decision about whether Q10 may be used as
     * a dimensionless remaining-envelope proxy.  The production calibration is intentionally
     * private and named as a proxy so callers cannot confuse it with measured Hall position.
     */
    public static final class Calibration {
        private final double centeredPlanarRadiusMm;
        private final double centeredRollRadiusDeg;
        private final RemainingProxy remainingProxy;

        public Calibration(
                double centeredPlanarRadiusMm,
                double centeredRollRadiusDeg,
                RemainingProxy remainingProxy) {
            if (!Double.isFinite(centeredPlanarRadiusMm) || centeredPlanarRadiusMm <= 0.0
                    || !Double.isFinite(centeredRollRadiusDeg)
                    || centeredRollRadiusDeg <= 0.0) {
                throw new IllegalArgumentException("tracking calibration limits must be positive");
            }
            this.centeredPlanarRadiusMm = centeredPlanarRadiusMm;
            this.centeredRollRadiusDeg = centeredRollRadiusDeg;
            this.remainingProxy = remainingProxy;
        }

        double getCenteredPlanarRadiusMm() { return centeredPlanarRadiusMm; }
        double getCenteredRollRadiusDeg() { return centeredRollRadiusDeg; }
        RemainingProxy getRemainingProxy() { return remainingProxy; }
    }

    /**
     * Explicit, reviewable bridge from a Q10 producer lane back to an estimated physical envelope.
     * It preserves the recovered correction-coordinate units but does not assert position
     * semantics for the filtered diagnostic producer.
     */
    public static final class RemainingProxy {
        private final int planarAccumulatorLimit;
        private final int rollAccumulatorLimit;
        private final double planarAccumulatorCountsPerMm;
        private final double rollAccumulatorCountsPerDeg;

        public RemainingProxy(
                int planarAccumulatorLimit,
                int rollAccumulatorLimit,
                double planarAccumulatorCountsPerMm,
                double rollAccumulatorCountsPerDeg) {
            if (planarAccumulatorLimit <= 0 || rollAccumulatorLimit <= 0
                    || !Double.isFinite(planarAccumulatorCountsPerMm)
                    || planarAccumulatorCountsPerMm <= 0.0
                    || !Double.isFinite(rollAccumulatorCountsPerDeg)
                    || rollAccumulatorCountsPerDeg <= 0.0) {
                throw new IllegalArgumentException("remaining-time proxy scales must be positive");
            }
            this.planarAccumulatorLimit = planarAccumulatorLimit;
            this.rollAccumulatorLimit = rollAccumulatorLimit;
            this.planarAccumulatorCountsPerMm = planarAccumulatorCountsPerMm;
            this.rollAccumulatorCountsPerDeg = rollAccumulatorCountsPerDeg;
        }
    }

    /** Immutable result. NaN denotes an intentionally unavailable product value. */
    public static final class Estimate {
        private static final Estimate UNAVAILABLE = new Estimate(Double.NaN, Double.NaN);

        private final double centeredSeconds;
        private final double remainingSeconds;

        private Estimate(double centeredSeconds, double remainingSeconds) {
            this.centeredSeconds = centeredSeconds;
            this.remainingSeconds = remainingSeconds;
        }

        public static Estimate unavailable() { return UNAVAILABLE; }
        public boolean hasCenteredSeconds() {
            return Double.isFinite(centeredSeconds) && centeredSeconds >= 0.0;
        }
        public boolean hasRemainingSeconds() {
            return Double.isFinite(remainingSeconds) && remainingSeconds >= 0.0;
        }
        public double getCenteredSeconds() { return centeredSeconds; }
        public double getRemainingSeconds() { return remainingSeconds; }
    }

    private TrackingExposureEstimator() {}

    /**
     * Returns the recovered, explicitly approximate correction-envelope estimate used by the UI.
     * The centered value uses conservative coupled geometry.  The remaining value treats the
     * stock Handheld Assist Q10 lanes as correction-coordinate equivalents only.
     */
    public static Estimate estimateRecoveredQ10Proxy(
            boolean correctionEnabled,
            boolean ratePossiblyActive,
            boolean rateHealthy,
            long correctionGeneration,
            long acknowledgedCorrectionGeneration,
            float focalLengthMm,
            SiderealRateCorrection acknowledgedRate,
            StarTelemetry.IbisSample ibisSample) {
        return estimate(
                RECOVERED_Q10_PROXY_CALIBRATION,
                correctionEnabled,
                ratePossiblyActive,
                rateHealthy,
                correctionGeneration,
                acknowledgedCorrectionGeneration,
                focalLengthMm,
                acknowledgedRate,
                ibisSample);
    }

    /**
     * Returns approximate display products only for the current acknowledged correction intent.
     * A renewal/write failure should pass {@code rateHealthy=false} immediately, even if the
     * transport conservatively retains a possibly-active lease bit.
     * Pass nominal camera focal with the actual acknowledged wire rates, which already include
     * any solved focal calibration; do not also scale the focal here.
     */
    public static Estimate estimate(
            Calibration calibration,
            boolean correctionEnabled,
            boolean ratePossiblyActive,
            boolean rateHealthy,
            long correctionGeneration,
            long acknowledgedCorrectionGeneration,
            float focalLengthMm,
            SiderealRateCorrection acknowledgedRate,
            StarTelemetry.IbisSample ibisSample) {
        if (calibration == null
                || !correctionEnabled
                || !ratePossiblyActive
                || !rateHealthy
                || correctionGeneration < 0L
                || acknowledgedCorrectionGeneration != correctionGeneration
                || acknowledgedRate == null
                || !Float.isFinite(focalLengthMm)
                || focalLengthMm <= 0.0f) {
            return Estimate.unavailable();
        }

        double quantizedFocalMm = Math.floor(
                focalLengthMm * FOCAL_QUANTIZATION_PER_MM) / FOCAL_QUANTIZATION_PER_MM;
        if (!(quantizedFocalMm > 0.0)) return Estimate.unavailable();

        double planarRateRadiansPerSecond = Math.hypot(
                acknowledgedRate.getYawRadiansPerSecond(),
                acknowledgedRate.getPitchRadiansPerSecond());
        double planarSpeedMmPerSecond =
                quantizedFocalMm * planarRateRadiansPerSecond;
        double rollSpeedDegPerSecond = DEGREES_PER_RADIAN
                * Math.abs(acknowledgedRate.getRollRadiansPerSecond());
        double centeredPlanarSeconds = timeToLimit(
                calibration.getCenteredPlanarRadiusMm(), planarSpeedMmPerSecond);
        double centeredRollSeconds = timeToLimit(
                calibration.getCenteredRollRadiusDeg(), rollSpeedDegPerSecond);
        double centeredSeconds = Math.min(centeredPlanarSeconds, centeredRollSeconds);
        if (!Double.isFinite(centeredSeconds)) return Estimate.unavailable();

        double remainingSeconds = Double.NaN;
        RemainingProxy proxy = calibration.getRemainingProxy();
        if (proxy != null && isQualifiedQ10(ibisSample)) {
            // The public producer truncates signed division while encoding Q10.  Use the largest
            // raw magnitude consistent with each observed bin so this proxy never gains headroom
            // merely from inverse-quantization rounding.
            double rawX = rawMagnitudeUpperBound(
                    ibisSample.getRawX(), proxy.planarAccumulatorLimit);
            double rawY = rawMagnitudeUpperBound(
                    ibisSample.getRawY(), proxy.planarAccumulatorLimit);
            double rawRoll = rawMagnitudeUpperBound(
                    ibisSample.getRawRoll(), proxy.rollAccumulatorLimit);
            double usedPlanarMm = Math.hypot(rawX, rawY)
                    / proxy.planarAccumulatorCountsPerMm;
            double usedRollDeg = rawRoll / proxy.rollAccumulatorCountsPerDeg;
            double remainingPlanarMm = Math.max(
                    0.0, calibration.getCenteredPlanarRadiusMm() - usedPlanarMm);
            double remainingRollDeg = Math.max(
                    0.0, calibration.getCenteredRollRadiusDeg() - usedRollDeg);
            double remainingPlanarSeconds = timeToLimit(
                    remainingPlanarMm, planarSpeedMmPerSecond);
            double remainingRollSeconds = timeToLimit(
                    remainingRollDeg, rollSpeedDegPerSecond);
            double candidate = Math.min(remainingPlanarSeconds, remainingRollSeconds);
            if (Double.isFinite(candidate) && candidate >= 0.0) remainingSeconds = candidate;
        }
        return new Estimate(centeredSeconds, remainingSeconds);
    }

    public static boolean isQualifiedQ10(StarTelemetry.IbisSample sample) {
        return sample != null
                && sample.isSourceMapped()
                && sample.isMirrorEnabled()
                && sample.isDoubleReadStable()
                // Full-scale is clamped and therefore has no finite inverse magnitude.
                && Math.abs(sample.getRawX()) < StarTelemetry.IBIS_Q10_FULL_SCALE
                && Math.abs(sample.getRawY()) < StarTelemetry.IBIS_Q10_FULL_SCALE
                && Math.abs(sample.getRawRoll()) < StarTelemetry.IBIS_Q10_FULL_SCALE;
    }

    private static double timeToLimit(double travel, double speed) {
        return speed > 0.0 && Double.isFinite(speed)
                ? travel / speed
                : Double.POSITIVE_INFINITY;
    }

    static int rawMagnitudeUpperBound(int q10, int rawLimit) {
        int magnitude = Math.abs(q10);
        if (magnitude >= StarTelemetry.IBIS_Q10_FULL_SCALE) return rawLimit;
        return Math.min(
                rawLimit,
                (int) Math.ceil((magnitude + 1.0) * rawLimit / Q10_FULL_SCALE) - 1);
    }
}
