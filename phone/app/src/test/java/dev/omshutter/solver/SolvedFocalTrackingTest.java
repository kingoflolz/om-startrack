package dev.omshutter.solver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import dev.omshutter.protocol.StarTelemetry;
import java.time.Instant;
import org.junit.Test;

/** Optical calibration scales actuator translation after the physical sky-rate calculation. */
public final class SolvedFocalTrackingTest {
    private static final Instant CAPTURE_UTC = Instant.parse("2026-09-12T04:00:00.125Z");
    private static final long CAPTURE_ELAPSED = 600_250_000_000L;
    private static final double LATITUDE = 35.0;
    private static final double LONGITUDE = -121.0;
    private static final double SUPPLIED_MM = 300.0;
    private static final double SOLVED_MM = 303.0;
    private static final double RATIO = SOLVED_MM / SUPPLIED_MM;

    @Test
    public void onePercentFocalCorrectionScalesOnlyFinalPlanarCommand() {
        SolvedTrackingModel physical = SolvedTrackingModel.withoutObserver(
                CorrectionAttitude.of(123.0, 35.0, -62.0), CAPTURE_UTC);
        SolvedTrackingModel calibrated = physical.withFocalCalibration(SUPPLIED_MM, SOLVED_MM);
        SiderealRateCorrection.BodyAngularVelocity body = physical.bodyRate(0.73);

        assertArrayEquals(body.toArray(), calibrated.bodyRate(0.73).toArray(), 0.0);
        assertEquals(scaledPlanar(body), calibrated.commandRate(0.73));
        assertEquals(physical.commandRate(0.73).getRollQ16(),
                calibrated.commandRate(0.73).getRollQ16());
        assertNotEquals(physical.commandRate(0.73).getYawQ16(),
                calibrated.commandRate(0.73).getYawQ16());
        assertNotEquals(physical.commandRate(0.73).getPitchQ16(),
                calibrated.commandRate(0.73).getPitchQ16());
    }

    @Test
    public void atmosphereAndDisplacedImageUseSolvedOpticsBeforeFinalPlanarScaling() {
        CorrectionAttitude attitude = attitudeAtAltitude(30.0);
        SolvedTrackingModel physical = withObserver(attitude);
        SolvedTrackingModel calibrated = physical.withFocalCalibration(SUPPLIED_MM, SOLVED_MM);
        StarTelemetry.IbisSample reference = q10(-100, 200);
        StarTelemetry.IbisSample latest = q10(600, -300);
        double factor = 0.73;
        SiderealRateCorrection.BodyAngularVelocity atSolvedOptics = apparentAtOffset(
                attitude, physical.geometricBodyRate(factor), 700, -500, SOLVED_MM);
        SiderealRateCorrection expected = scaledPlanar(atSolvedOptics);

        assertEquals(expected, calibrated.rateAtQ10(factor, reference, latest, SUPPLIED_MM));
        assertNotEquals(scaledPlanar(apparentAtOffset(attitude,
                        physical.geometricBodyRate(factor), 700, -500, SUPPLIED_MM)), expected);

        // A quantized intermediate loses information before the focal-ratio multiplication.
        SiderealRateCorrection premature = atSolvedOptics.toRateCorrection();
        SiderealRateCorrection scaledAfterQuantizing = SiderealRateCorrection.fromBodyAngularVelocity(
                premature.getPitchRadiansPerSecond() * RATIO,
                premature.getYawRadiansPerSecond() * RATIO,
                -premature.getRollRadiansPerSecond());
        assertNotEquals(scaledAfterQuantizing, expected);
    }

    @Test
    public void nearbyLiveFocalChangeUsesTheRetainedRatioRatherThanCancellingZoom() {
        CorrectionAttitude attitude = attitudeAtAltitude(30.0);
        SolvedTrackingModel physical = withObserver(attitude);
        SolvedTrackingModel calibrated = physical.withFocalCalibration(SUPPLIED_MM, SOLVED_MM);
        StarTelemetry.IbisSample reference = q10(-100, 200);
        StarTelemetry.IbisSample latest = q10(600, -300);
        double currentNominalMm = 306.0;
        double currentOpticalMm = 309.06;
        SiderealRateCorrection expected = scaledPlanar(apparentAtOffset(
                attitude, physical.geometricBodyRate(1.0), 700, -500, currentOpticalMm));

        assertEquals(expected, calibrated.rateAtQ10(1.0, reference, latest, currentNominalMm));
        assertNotEquals(calibrated.rateAtQ10(1.0, reference, latest, SUPPLIED_MM), expected);
        assertEquals(calibrated.commandRate(1.0),
                calibrated.rateAtQ10(1.0, null, latest, currentNominalMm));
    }

    @Test
    public void observerReplacementRetainsCalibrationAndLeavesOldModelImmutable() {
        CorrectionAttitude attitude = attitudeAtAltitude(30.0);
        SolvedTrackingModel original = SolvedTrackingModel.withoutObserver(attitude, CAPTURE_UTC);
        SiderealRateCorrection oldCommand = original.commandRate(1.0);
        SolvedTrackingModel calibrationFirst = original.withFocalCalibration(SUPPLIED_MM, SOLVED_MM)
                .withObserver(observerAfterSeconds(120), CAPTURE_ELAPSED);
        SolvedTrackingModel observerFirst = original.withObserver(observerAfterSeconds(0), CAPTURE_ELAPSED)
                .withFocalCalibration(SUPPLIED_MM, SOLVED_MM);
        SolvedTrackingModel laterFix = calibrationFirst.withObserver(
                observerAfterSeconds(7_200), CAPTURE_ELAPSED);

        assertEquals(observerFirst.commandRate(1.0), calibrationFirst.commandRate(1.0));
        assertEquals(observerFirst.commandRate(1.0), laterFix.commandRate(1.0));
        assertEquals(scaledPlanar(withObserver(attitude).bodyRate(1.0)), laterFix.commandRate(1.0));
        assertEquals(oldCommand, original.commandRate(1.0));
    }

    @Test
    public void prepositionAndTravelEstimateUseNominalFocalWithTheCalibratedAcknowledgedRate() {
        SolvedTrackingModel original = SolvedTrackingModel.withoutObserver(
                CorrectionAttitude.of(123.0, 0.0, 37.0), CAPTURE_UTC);
        SiderealRateCorrection raw = original.commandRate(1.0);
        SiderealRateCorrection calibrated = original.withFocalCalibration(SUPPLIED_MM, SOLVED_MM)
                .commandRate(1.0);

        // Correcting the wire rate at 300 mm must agree with a real 303-mm optical sweep.
        IbisPrepositionPlanner.Plan actual = IbisPrepositionPlanner.plan(300.0f, calibrated, 30.0);
        IbisPrepositionPlanner.Plan opticalEquivalent = IbisPrepositionPlanner.plan(303.0f, raw, 30.0);
        IbisPrepositionPlanner.Plan doubleScaled = IbisPrepositionPlanner.plan(303.0f, calibrated, 30.0);
        assertTrue(actual.isAvailable());
        assertEquals(opticalEquivalent.getLogicalX(), actual.getLogicalX(), 1.0);
        assertEquals(opticalEquivalent.getLogicalY(), actual.getLogicalY(), 1.0);
        assertEquals(opticalEquivalent.getLogicalRoll(), actual.getLogicalRoll());
        // Native gain and wire rounding introduce tiny differences; a second 1% scaling does not.
        assertEquals(opticalEquivalent.getMaximumSeconds(), actual.getMaximumSeconds(), 0.001);
        assertTrue(Math.abs(doubleScaled.getMaximumSeconds() - actual.getMaximumSeconds()) > 0.1);

        TrackingExposureEstimator.Estimate actualTravel = travel(300.0f, calibrated);
        TrackingExposureEstimator.Estimate opticalTravel = travel(303.0f, raw);
        TrackingExposureEstimator.Estimate doubleScaledTravel = travel(303.0f, calibrated);
        assertTrue(actualTravel.hasCenteredSeconds());
        assertEquals(opticalTravel.getCenteredSeconds(), actualTravel.getCenteredSeconds(), 0.0001);
        assertEquals(opticalTravel.getRemainingSeconds(), actualTravel.getRemainingSeconds(), 0.0001);
        assertTrue(Math.abs(doubleScaledTravel.getCenteredSeconds()
                - actualTravel.getCenteredSeconds()) > 0.1);
    }

    private static TrackingExposureEstimator.Estimate travel(
            float nominalFocalMm, SiderealRateCorrection acknowledged) {
        return TrackingExposureEstimator.estimateRecoveredQ10Proxy(
                true, true, true, 7L, 7L, nominalFocalMm, acknowledged, q10(20, -30));
    }

    private static SiderealRateCorrection scaledPlanar(SiderealRateCorrection.BodyAngularVelocity body) {
        return SiderealRateCorrection.fromBodyAngularVelocity(
                body.getXRadiansPerSecond() * RATIO,
                body.getYRadiansPerSecond() * RATIO,
                body.getZRadiansPerSecond());
    }

    private static SiderealRateCorrection.BodyAngularVelocity apparentAtOffset(
            CorrectionAttitude attitude, SiderealRateCorrection.BodyAngularVelocity geometric,
            int deltaX, int deltaY, double effectiveFocalMm) {
        double[] zenith = AtmosphericRefractionCorrection.zenithBody(
                attitude.getRightAscensionDeg(), attitude.getDeclinationDeg(), attitude.getRollDeg(),
                observerAfterSeconds(0), CAPTURE_ELAPSED);
        return AtmosphericRefractionCorrection.correctAtGnomonic(geometric, zenith,
                AtmosphericRefractionCorrection.Atmosphere.standard(),
                deltaX * SolveLatchedQ10RateAdjuster.PLANAR_MM_PER_Q10_COUNT / effectiveFocalMm,
                deltaY * SolveLatchedQ10RateAdjuster.PLANAR_MM_PER_Q10_COUNT / effectiveFocalMm)
                .getBodyAngularVelocity();
    }

    private static SolvedTrackingModel withObserver(CorrectionAttitude attitude) {
        return SolvedTrackingModel.withoutObserver(attitude, CAPTURE_UTC)
                .withObserver(observerAfterSeconds(0), CAPTURE_ELAPSED);
    }

    private static CorrectionAttitude attitudeAtAltitude(double altitude) {
        EquatorialCoordinates pointing = EquatorialCoordinates.fromHorizontal(
                altitude, 110.0, LATITUDE, LONGITUDE,
                CAPTURE_UTC.getEpochSecond(), CAPTURE_UTC.getNano());
        return CorrectionAttitude.of(pointing.getRightAscensionDeg(), pointing.getDeclinationDeg(), 31.0);
    }

    private static ObserverCoordinateContext observerAfterSeconds(long seconds) {
        Instant receipt = CAPTURE_UTC.plusSeconds(seconds);
        return ObserverCoordinateContext.of(LATITUDE, LONGITUDE, receipt.getEpochSecond(), receipt.getNano(),
                CAPTURE_ELAPSED + seconds * 1_000_000_000L);
    }

    private static StarTelemetry.IbisSample q10(int x, int y) {
        int flags = StarTelemetry.IBIS_FLAG_SOURCE_MAPPED
                | StarTelemetry.IBIS_FLAG_MIRROR_ENABLED
                | StarTelemetry.IBIS_FLAG_DOUBLE_READ_STABLE;
        return new StarTelemetry.IbisSample(flags, 1, x, y, 0);
    }
}
