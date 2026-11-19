package dev.omshutter.solver;

import java.time.Instant;
import dev.omshutter.protocol.StarTelemetry;

/** Immutable celestial/atmospheric reference belonging to one solved image. */
public final class SolvedTrackingModel {
    private static final AtmosphericRefractionCorrection.Atmosphere ATMOSPHERE =
            AtmosphericRefractionCorrection.Atmosphere.standard();

    private final CorrectionAttitude attitude;
    private final Instant captureUtc;
    private final double[] zenithBody;
    private final ObserverCoordinateContext observer;
    private final long captureElapsedRealtimeNanos;
    private final PlanarFocalCalibration focalCalibration;

    private SolvedTrackingModel(CorrectionAttitude attitude, Instant captureUtc,
            double[] zenithBody, ObserverCoordinateContext observer, long captureElapsedRealtimeNanos,
            PlanarFocalCalibration focalCalibration) {
        if (attitude == null || captureUtc == null) {
            throw new IllegalArgumentException("solve attitude and date are required");
        }
        this.attitude = attitude;
        this.captureUtc = captureUtc;
        this.zenithBody = zenithBody;
        this.observer = observer;
        this.captureElapsedRealtimeNanos = captureElapsedRealtimeNanos;
        this.focalCalibration = focalCalibration;
    }

    /** The date alone suffices for the rotation pole; refraction waits for a capture UTC fix. */
    public static SolvedTrackingModel withoutObserver(CorrectionAttitude attitude, Instant utc) {
        return new SolvedTrackingModel(attitude, utc, null, null, -1L, null);
    }

    /** Retains the central image-scale calibration from this same successful solve. */
    public SolvedTrackingModel withFocalCalibration(double suppliedMm, double solvedMm) {
        return new SolvedTrackingModel(attitude, captureUtc, zenithBody, observer,
                captureElapsedRealtimeNanos, PlanarFocalCalibration.of(suppliedMm, solvedMm));
    }

    /**
     * Freeze local zenith at capture, including when the location fix arrives after the solve.
     * Evaluating the old RA/Dec at heartbeat UTC would incorrectly move this fixed camera frame.
     */
    public SolvedTrackingModel withObserver(ObserverCoordinateContext observer,
            long captureElapsedRealtimeNanos) {
        if (observer == null) throw new IllegalArgumentException("observer is unavailable");
        if (this.observer == observer
                && this.captureElapsedRealtimeNanos == captureElapsedRealtimeNanos) return this;
        ObserverCoordinateContext.UtcInstant utc =
                observer.utcAtCaptureElapsed(captureElapsedRealtimeNanos);
        Instant instant = Instant.ofEpochSecond(utc.getEpochSecond(), utc.getNano());
        return new SolvedTrackingModel(attitude, instant,
                AtmosphericRefractionCorrection.zenithBody(
                        attitude.getRightAscensionDeg(), attitude.getDeclinationDeg(),
                        attitude.getRollDeg(), observer, captureElapsedRealtimeNanos),
                observer, captureElapsedRealtimeNanos, focalCalibration);
    }

    public SiderealRateCorrection.BodyAngularVelocity geometricBodyRate(double factor) {
        return SiderealRateCorrection.bodyAngularVelocityFromEquatorialAttitude(
                attitude.getRightAscensionDeg(), attitude.getDeclinationDeg(),
                attitude.getRollDeg(), factor, captureUtc);
    }

    public SiderealRateCorrection.BodyAngularVelocity bodyRate(double factor) {
        SiderealRateCorrection.BodyAngularVelocity geometric = geometricBodyRate(factor);
        return zenithBody == null ? geometric : AtmosphericRefractionCorrection.correct(
                geometric, zenithBody, ATMOSPHERE).getBodyAngularVelocity();
    }

    /** Camera command: physical apparent rate, with only translation calibrated for FLact. */
    public SiderealRateCorrection commandRate(double factor) {
        return calibratedCommand(bodyRate(factor));
    }

    private SiderealRateCorrection calibratedCommand(
            SiderealRateCorrection.BodyAngularVelocity physicalRate) {
        return (focalCalibration == null ? physicalRate : focalCalibration.apply(physicalRate))
                .toRateCorrection();
    }

    /**
     * Re-evaluates the apparent velocity field at the qualified sensor displacement. Missing
     * solve Q10 permits only the origin rate; unusable live telemetry keeps the ACKed rate.
     */
    public SiderealRateCorrection rateAtQ10(double factor,
            StarTelemetry.IbisSample reference,
            StarTelemetry.IbisSample latest, double focalLengthMm) {
        if (reference == null) return commandRate(factor);
        if (!Double.isFinite(focalLengthMm) || focalLengthMm <= 0.0) return null;
        double effectiveFocal = focalCalibration == null ? focalLengthMm
                : focalCalibration.effectiveFocalLengthMm(focalLengthMm);
        SiderealRateCorrection.BodyAngularVelocity geometric = geometricBodyRate(factor);
        SolveLatchedQ10RateAdjuster.Result offset = SolveLatchedQ10RateAdjuster.adjustBaseline(
                geometric, reference, latest, effectiveFocal,
                SolveLatchedQ10RateAdjuster.DEFAULT_AXIS_MAPPING);
        if (!offset.isApplied()) return null;
        if (zenithBody == null) return calibratedCommand(offset.getBodyAngularVelocity());
        return calibratedCommand(AtmosphericRefractionCorrection.correctAtGnomonic(geometric, zenithBody,
                ATMOSPHERE, offset.getImageRightGnomonic(), offset.getImageDownGnomonic())
                .getBodyAngularVelocity());
    }

    public String refractionStatus() {
        if (zenithBody == null) return "Refraction unavailable: capture time/location needed";
        AtmosphericRefractionCorrection.Result result = AtmosphericRefractionCorrection.correct(
                geometricBodyRate(1.0), zenithBody, ATMOSPHERE);
        return result.isApplied()
                ? "Refraction model: standard atmosphere (1013 hPa, 15 °C)"
                : "Refraction off: target below 10° altitude";
    }
}
