package dev.omshutter.solver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.omshutter.protocol.StarTelemetry;

import java.time.Instant;

import org.junit.Test;

public final class SolvedTrackingModelTest {
    private static final Instant CAPTURE_UTC = Instant.parse("2026-09-12T04:00:00.125Z");
    private static final long CAPTURE_ELAPSED = 600_250_000_000L;
    private static final double LATITUDE = 35.0;
    private static final double LONGITUDE = -121.0;

    @Test
    public void missingObserverKeepsDatedGeometricRateAndWithholdsRefraction() {
        CorrectionAttitude attitude = attitudeAtAltitude(30.0);
        SolvedTrackingModel model = SolvedTrackingModel.withoutObserver(attitude, CAPTURE_UTC);
        SiderealRateCorrection.BodyAngularVelocity dated =
                SiderealRateCorrection.bodyAngularVelocityFromEquatorialAttitude(
                        attitude.getRightAscensionDeg(), attitude.getDeclinationDeg(),
                        attitude.getRollDeg(), 0.7, CAPTURE_UTC);

        assertArrayEquals(dated.toArray(), model.geometricBodyRate(0.7).toArray(), 0.0);
        assertArrayEquals(dated.toArray(), model.bodyRate(0.7).toArray(), 0.0);
        assertEquals(0.7 * SiderealRateCorrection.SIDEREAL_RATE_RAD_PER_SECOND,
                norm(model.bodyRate(0.7).toArray()), 1e-18);
        assertTrue(model.refractionStatus().contains("unavailable"));
        assertTrue(model.refractionStatus().contains("capture time/location"));
    }

    @Test
    public void observerArrivingAfterCaptureRecoversCaptureUtcAndHorizon() {
        CorrectionAttitude attitude = attitudeAtAltitude(30.0);
        // The initial wall-clock date is only a fallback; the later trusted fix supplies
        // the capture's actual UTC through the earlier monotonic timestamp.
        SolvedTrackingModel pending = SolvedTrackingModel.withoutObserver(
                attitude, CAPTURE_UTC.plusSeconds(3_600));
        SolvedTrackingModel late = pending.withObserver(
                observerAfterNanos(120_375_000_000L), CAPTURE_ELAPSED);
        SolvedTrackingModel immediate = SolvedTrackingModel.withoutObserver(
                attitude, CAPTURE_UTC).withObserver(observerAfterNanos(0L), CAPTURE_ELAPSED);

        assertArrayEquals(immediate.geometricBodyRate(1.0).toArray(),
                late.geometricBodyRate(1.0).toArray(), 0.0);
        assertArrayEquals(immediate.bodyRate(1.0).toArray(), late.bodyRate(1.0).toArray(), 0.0);
        assertTrue(late.refractionStatus().contains("standard atmosphere"));
        assertTrue(distance(late.bodyRate(1.0).toArray(),
                late.geometricBodyRate(1.0).toArray()) > 1e-9);
        assertTrue(pending.refractionStatus().contains("unavailable"));
    }

    @Test
    public void consistentLaterObserverFixDoesNotAdvanceTheFrozenSolveAttitude() {
        CorrectionAttitude attitude = attitudeAtAltitude(30.0);
        SolvedTrackingModel original = SolvedTrackingModel.withoutObserver(attitude, CAPTURE_UTC)
                .withObserver(observerAfterNanos(0L), CAPTURE_ELAPSED);
        long twoHours = 7_200_000_000_000L;
        ObserverCoordinateContext laterFix = observerAfterNanos(twoHours);
        SolvedTrackingModel refreshed = original.withObserver(laterFix, CAPTURE_ELAPSED);

        assertNotSame(original, refreshed);
        assertArrayEquals(original.geometricBodyRate(1.0).toArray(),
                refreshed.geometricBodyRate(1.0).toArray(), 0.0);
        assertArrayEquals(original.bodyRate(1.0).toArray(), refreshed.bodyRate(1.0).toArray(), 0.0);

        // Prove this fixture detects treating the old catalog direction as a new capture.
        SolvedTrackingModel incorrectlyAdvanced = original.withObserver(
                laterFix, CAPTURE_ELAPSED + twoHours);
        assertTrue(distance(original.bodyRate(1.0).toArray(),
                incorrectlyAdvanced.bodyRate(1.0).toArray()) > 1e-9);
    }

    @Test
    public void replacementObserverLeavesTheOldCaptureModelImmutable() {
        CorrectionAttitude attitude = attitudeAtAltitude(30.0);
        SolvedTrackingModel old = SolvedTrackingModel.withoutObserver(attitude, CAPTURE_UTC)
                .withObserver(observerAfterNanos(0L), CAPTURE_ELAPSED);
        double[] retainedRate = old.bodyRate(1.0).toArray();
        String retainedStatus = old.refractionStatus();
        ObserverCoordinateContext differentSite = ObserverCoordinateContext.of(
                LATITUDE, LONGITUDE + 30.0, CAPTURE_UTC.getEpochSecond(), CAPTURE_UTC.getNano(),
                CAPTURE_ELAPSED);
        SolvedTrackingModel replacement = old.withObserver(differentSite, CAPTURE_ELAPSED);

        assertNotSame(old, replacement);
        assertTrue(distance(retainedRate, replacement.bodyRate(1.0).toArray()) > 1e-9);
        assertArrayEquals(retainedRate, old.bodyRate(1.0).toArray(), 0.0);
        assertEquals(retainedStatus, old.refractionStatus());
        double[] callerCopy = old.bodyRate(1.0).toArray();
        callerCopy[0] = 42.0;
        assertArrayEquals(retainedRate, old.bodyRate(1.0).toArray(), 0.0);
    }

    @Test
    public void strengthScalesEveryCorrectedComponentExactlyOnce() {
        SolvedTrackingModel model = SolvedTrackingModel.withoutObserver(
                attitudeAtAltitude(30.0), CAPTURE_UTC)
                .withObserver(observerAfterNanos(0L), CAPTURE_ELAPSED);
        double[] full = model.bodyRate(1.0).toArray();
        double[] partial = model.bodyRate(0.37).toArray();
        for (int axis = 0; axis < 3; axis++) {
            assertTrue("fixture exercises axis " + axis, Math.abs(full[axis]) > 1e-8);
            assertEquals(0.37 * full[axis], partial[axis], 1e-18);
        }
        assertArrayEquals(new double[3], model.bodyRate(0.0).toArray(), 0.0);
    }

    @Test
    public void lowAltitudeKeepsGeometricRateAndReportsRefractionGuard() {
        SolvedTrackingModel model = SolvedTrackingModel.withoutObserver(
                attitudeAtAltitude(5.0), CAPTURE_UTC)
                .withObserver(observerAfterNanos(45_000_000_000L), CAPTURE_ELAPSED);

        assertArrayEquals(model.geometricBodyRate(1.0).toArray(),
                model.bodyRate(1.0).toArray(), 0.0);
        assertTrue(model.refractionStatus().contains("below 10°"));
    }

    @Test
    public void withoutObserverQualifiedQ10UsesTheFrozenDatedGeometricModel() {
        CorrectionAttitude attitude = attitudeAtAltitude(30.0);
        SolvedTrackingModel model = SolvedTrackingModel.withoutObserver(attitude, CAPTURE_UTC);
        StarTelemetry.IbisSample reference = q10(20, -30);
        StarTelemetry.IbisSample latest = q10(500, -350);
        SolveLatchedQ10RateAdjuster.Result expected = SolveLatchedQ10RateAdjuster.adjust(
                attitude, 0.7, reference, latest, 50.0,
                SolveLatchedQ10RateAdjuster.DEFAULT_AXIS_MAPPING, CAPTURE_UTC);

        assertTrue(expected.isApplied());
        assertEquals(expected.getCorrection(), model.rateAtQ10(0.7, reference, latest, 50.0));
    }

    @Test
    public void displacedAtmosphericRateReevaluatesRefractionInsteadOfReusingOriginRate() {
        CorrectionAttitude attitude = attitudeAtAltitude(30.0);
        ObserverCoordinateContext observer = observerAfterNanos(120_375_000_000L);
        SolvedTrackingModel model = SolvedTrackingModel.withoutObserver(attitude, CAPTURE_UTC)
                .withObserver(observer, CAPTURE_ELAPSED);
        StarTelemetry.IbisSample reference = q10(20, -30);
        StarTelemetry.IbisSample latest = q10(500, -350);
        double u = 480 * SolveLatchedQ10RateAdjuster.PLANAR_MM_PER_Q10_COUNT / 50.0;
        double v = -320 * SolveLatchedQ10RateAdjuster.PLANAR_MM_PER_Q10_COUNT / 50.0;
        double[] zenith = AtmosphericRefractionCorrection.zenithBody(
                attitude.getRightAscensionDeg(), attitude.getDeclinationDeg(),
                attitude.getRollDeg(), observer, CAPTURE_ELAPSED);
        SiderealRateCorrection expected = AtmosphericRefractionCorrection.correctAtGnomonic(
                model.geometricBodyRate(1.0), zenith,
                AtmosphericRefractionCorrection.Atmosphere.standard(), u, v)
                .getBodyAngularVelocity().toRateCorrection();
        SiderealRateCorrection incorrectlyReusedOrigin = SolveLatchedQ10RateAdjuster.adjustBaseline(
                model.bodyRate(1.0), reference, latest, 50.0,
                SolveLatchedQ10RateAdjuster.DEFAULT_AXIS_MAPPING).getCorrection();

        assertEquals(expected, model.rateAtQ10(1.0, reference, latest, 50.0));
        assertNotEquals(incorrectlyReusedOrigin, expected);
        assertEquals(expected, model.withObserver(observerAfterNanos(7_200_000_000_000L),
                CAPTURE_ELAPSED).rateAtQ10(1.0, reference, latest, 50.0));
    }

    @Test
    public void missingReferenceUsesOriginButUnusableLatestWithReferenceKeepsAckedRate() {
        SolvedTrackingModel model = SolvedTrackingModel.withoutObserver(
                attitudeAtAltitude(30.0), CAPTURE_UTC)
                .withObserver(observerAfterNanos(0L), CAPTURE_ELAPSED);
        StarTelemetry.IbisSample reference = q10(20, -30);
        StarTelemetry.IbisSample invalid =
                new StarTelemetry.IbisSample(0, 2, 500, -350, 0);

        assertEquals(model.bodyRate(1.0).toRateCorrection(),
                model.rateAtQ10(1.0, null, invalid, 50.0));
        assertEquals(model.bodyRate(1.0).toRateCorrection(),
                model.rateAtQ10(1.0, reference, reference, 50.0));
        assertNull(model.rateAtQ10(1.0, reference, invalid, 50.0));
        assertNull(model.rateAtQ10(1.0, reference, null, 50.0));
        assertNull(model.rateAtQ10(1.0, reference, q10(960, 0), 50.0));
    }

    @Test
    public void zeroStrengthStaysExactZeroWithAtmosphereAndNonzeroQ10Offset() {
        SolvedTrackingModel model = SolvedTrackingModel.withoutObserver(
                attitudeAtAltitude(30.0), CAPTURE_UTC)
                .withObserver(observerAfterNanos(0L), CAPTURE_ELAPSED);
        SiderealRateCorrection stopped = model.rateAtQ10(
                0.0, q10(20, -30), q10(500, -350), 50.0);

        assertEquals(0, stopped.getYawQ16());
        assertEquals(0, stopped.getPitchQ16());
        assertEquals(0, stopped.getRollQ16());
    }

    private static CorrectionAttitude attitudeAtAltitude(double altitude) {
        EquatorialCoordinates pointing = EquatorialCoordinates.fromHorizontal(
                altitude, 110.0, LATITUDE, LONGITUDE,
                CAPTURE_UTC.getEpochSecond(), CAPTURE_UTC.getNano());
        return CorrectionAttitude.of(pointing.getRightAscensionDeg(),
                pointing.getDeclinationDeg(), 31.0);
    }

    private static ObserverCoordinateContext observerAfterNanos(long delay) {
        Instant receiptUtc = CAPTURE_UTC.plusNanos(delay);
        return ObserverCoordinateContext.of(LATITUDE, LONGITUDE,
                receiptUtc.getEpochSecond(), receiptUtc.getNano(), CAPTURE_ELAPSED + delay);
    }

    private static StarTelemetry.IbisSample q10(int x, int y) {
        int flags = StarTelemetry.IBIS_FLAG_SOURCE_MAPPED
                | StarTelemetry.IBIS_FLAG_MIRROR_ENABLED
                | StarTelemetry.IBIS_FLAG_DOUBLE_READ_STABLE;
        return new StarTelemetry.IbisSample(flags, 1, x, y, 0);
    }

    private static double distance(double[] left, double[] right) {
        return Math.hypot(Math.hypot(left[0] - right[0], left[1] - right[1]),
                left[2] - right[2]);
    }

    private static double norm(double[] vector) {
        return Math.hypot(Math.hypot(vector[0], vector[1]), vector[2]);
    }
}
