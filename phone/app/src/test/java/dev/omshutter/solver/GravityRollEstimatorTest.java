package dev.omshutter.solver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import org.junit.Test;

public final class GravityRollEstimatorTest {
    private static final long J2000_NOON_SECONDS = 946_728_000L;
    private static final long NOW = 1_000_000_000_000L;
    private static final double J2000_ERA_DEG = 280.46061837504;
    private static final double J2000_GMST_DEG = 280.4606224044844;
    private static final long SAMPLE_PERIOD_NANOS = 100_000_000L;

    @Test
    public void earthRotationAndGmstLockIauReferenceVectorsAndUtcBoundaries() {
        assertEquals(
                J2000_ERA_DEG,
                Math.toDegrees(GravityRollEstimator.earthRotationAngleRadians(
                        J2000_NOON_SECONDS, 0)),
                1.0e-10);
        assertTrue(GravityRollEstimator.earthRotationAngleRadians(
                J2000_NOON_SECONDS, 999_999_999) >= 0.0);
        assertEquals(
                J2000_GMST_DEG,
                Math.toDegrees(GravityRollEstimator.greenwichMeanSiderealTimeRadians(
                        J2000_NOON_SECONDS, 0)),
                1.0e-10);
        // IAU SOFA t_gmst06 reference: UT1=TT=JD 2453736.5.
        assertEquals(
                1.754174971870091203,
                GravityRollEstimator.greenwichMeanSiderealTimeRadians(
                        1_136_073_600L, 0),
                1.0e-12);
        assertThrows(
                IllegalArgumentException.class,
                () -> GravityRollEstimator.earthRotationAngleRadians(
                        J2000_NOON_SECONDS, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> GravityRollEstimator.earthRotationAngleRadians(
                        J2000_NOON_SECONDS, 1_000_000_000));
        assertThrows(
                IllegalArgumentException.class,
                () -> GravityRollEstimator.earthRotationAngleRadians(-1L, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> GravityRollEstimator.earthRotationAngleRadians(
                        GravityRollEstimator.MAX_UTC_EPOCH_SECOND + 1L, 0));
    }

    @Test
    public void biasPrecessionMatrixLocksPublishedIau2006Reference() {
        // IAU SOFA pmat06 validation vector at TT MJD 50123.9999.
        double[][] matrix = GravityRollEstimator.icrsToMeanEquatorOfDateMatrix(
                823_996_791L, 360_000_000);
        double[][] expected = {
            {
                0.9999995505176007047,
                0.8695404617348208406e-3,
                0.3779735201865589104e-3
            },
            {
                -0.8695404723772031414e-3,
                0.9999996219496027161,
                -0.1361752497080270143e-6
            },
            {
                -0.3779734957034089490e-3,
                -0.1924880847894457113e-6,
                0.9999999285679971958
            }
        };
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                assertEquals(expected[row][column], matrix[row][column], 1.0e-14);
            }
        }
    }

    @Test
    public void realDateZenithVectorKeepsIcrsAndMeanDateFramesCoherent() {
        // Independently evaluated with IAU 2006 pmat06+gmst06 at
        // 2026-08-21T12:34:56Z, RA=123.456, Dec=-20.25,
        // latitude=37.7749, longitude=-122.4194 east-positive.
        double[] zenith = GravityRollEstimator.celestialZenithComponents(
                Math.toRadians(123.456),
                Math.toRadians(-20.25),
                Math.toRadians(37.7749),
                Math.toRadians(-122.4194),
                1_787_315_696L,
                0);
        assertEquals(-0.79106869898675425, zenith[0], 1.0e-12);
        assertEquals(0.58412766324849308, zenith[1], 1.0e-12);
        assertEquals(-0.18167329608739768, zenith[2], 1.0e-12);
        assertEquals(-53.557721098564983,
                Math.toDegrees(Math.atan2(zenith[0], zenith[1])), 1.0e-10);
        assertEquals(-10.46723975975163,
                Math.toDegrees(Math.asin(zenith[2])), 1.0e-10);
    }

    @Test
    public void uprightMeridianVectorLocksZeroRollAndWireSigns() {
        GravityRollEstimator.Result result = deriveAtHourAngle(
                45.0, 0.0, 0.0, 0, 707, -707);

        assertValidAngle(result, 0.0, 0.02);
        SiderealRateCorrection correction = SiderealRateCorrection.fromEquatorialAttitude(
                0.0, 0.0, result.getImageUpPositionAngleDeg(), 1.0,
                Instant.ofEpochSecond(J2000_NOON_SECONDS));
        assertCounts(correction, -729, 0, 0);
    }

    @Test
    public void quarterTurnVectorLocksImageAndWireAxes() {
        GravityRollEstimator.Result result = deriveAtHourAngle(
                45.0, 0.0, 0.0, -707, 0, -707);

        assertValidAngle(result, 90.0, 0.02);
        SiderealRateCorrection correction = SiderealRateCorrection.fromEquatorialAttitude(
                0.0, 0.0, result.getImageUpPositionAngleDeg(), 1.0,
                Instant.ofEpochSecond(J2000_NOON_SECONDS));
        assertCounts(correction, 0, 729, 0);
    }

    @Test
    public void offCardinalVectorLocksParallacticSignsAndExistingRateMapping() {
        GravityRollEstimator.Result result = deriveAtHourAngle(
                32.0, 20.0, -55.0, 100, -763, -638);

        assertValidAngle(result, 123.0, 0.03);
        SiderealRateCorrection correction = SiderealRateCorrection.fromEquatorialAttitude(
                0.0, 20.0, result.getImageUpPositionAngleDeg(), 1.0,
                Instant.ofEpochSecond(J2000_NOON_SECONDS));
        assertCounts(correction, 373, 575, -249);
    }

    @Test
    public void projectionThresholdAcceptsPointTwoAndRejectsBelowIt() {
        CameraGravityWindow gravity = stableGravity(210, 0, -978, NOW);
        GravityRollEstimator.Result boundary = GravityRollEstimator.deriveQualified(
                0.20,
                0.0,
                Math.sqrt(1.0 - 0.20 * 0.20),
                gravity.estimate(NOW));
        assertEquals(GravityRollEstimator.Status.VALID, boundary.getStatus());
        assertEquals(0.20, boundary.getProjection(), 1.0e-12);

        GravityRollEstimator.Result below = GravityRollEstimator.deriveQualified(
                0.199,
                0.0,
                Math.sqrt(1.0 - 0.199 * 0.199),
                gravity.estimate(NOW));
        assertFailure(
                below,
                GravityRollEstimator.Status.SINGULAR,
                "Too close to vertical");
    }

    @Test
    public void exactZenithAndNadirFailClosed() {
        GravityRollEstimator.Result zenith = deriveAtHourAngle(
                45.0, 45.0, 0.0, 0, 0, -1_000);
        assertFailure(
                zenith,
                GravityRollEstimator.Status.SINGULAR,
                "Too close to vertical");

        GravityRollEstimator.Result nadir = deriveAtHourAngle(
                -45.0, 45.0, 180.0, 0, 0, 1_000);
        assertFailure(
                nadir,
                GravityRollEstimator.Status.SINGULAR,
                "Too close to vertical");
    }

    @Test
    public void altitudeAgreementAcceptsBelowThreeDegreesAndRejectsAboveIt() {
        GravityRollEstimator.Result accepted = deriveAtHourAngle(
                0.0, 0.0, 30.0, 481, 0, -944);
        assertEquals(GravityRollEstimator.Status.VALID, accepted.getStatus());

        GravityRollEstimator.Result rejected = deriveAtHourAngle(
                0.0, 0.0, 30.0, 428, 0, -840);
        assertFailure(
                rejected,
                GravityRollEstimator.Status.MISMATCH,
                "RA/Dec doesn't match camera tilt");
    }

    @Test
    public void eastLongitudeIncreasesAndRightAscensionDecreasesHourAngle() {
        GravityRollEstimator.Result base = deriveWithExplicitRaLongitude(
                45.0, 0.0, 0.0, longitudeForHourAngle(30.0), 354, 707, -612);
        assertValidAngle(base, 0.0, 0.08);

        // At fixed longitude, +15 degrees of RA changes H from +30 to +15 degrees.
        GravityRollEstimator.Result greaterRa = deriveWithExplicitRaLongitude(
                45.0, 0.0, 15.0, longitudeForHourAngle(30.0), 183, 707, -683);
        assertValidAngle(greaterRa, 0.0, 0.08);

        // At fixed RA, +15 degrees east longitude changes H from +30 to +45 degrees.
        GravityRollEstimator.Result eastLongitude = deriveWithExplicitRaLongitude(
                45.0, 0.0, 0.0, longitudeForHourAngle(45.0), 500, 707, -500);
        assertValidAngle(eastLongitude, 0.0, 0.08);
    }

    @Test
    public void missingUnstableAndMismatchReasonsAreCompactAndExact() {
        ObserverLocationFix location = locationAtHourAngle(45.0, 0.0, 0.0, NOW);
        ManualEquatorialPointing pointing = ManualEquatorialPointing.of(0.0, 0.0, NOW);
        CameraGravityWindow stable = stableGravity(0, 707, -707, NOW);

        assertFailure(
                GravityRollEstimator.derive(
                        null, location, stable.estimate(NOW),
                        J2000_NOON_SECONDS, 0, NOW),
                GravityRollEstimator.Status.MISSING_POINTING,
                "RA/Dec required");
        assertFailure(
                GravityRollEstimator.derive(
                        pointing, null, stable.estimate(NOW),
                        J2000_NOON_SECONDS, 0, NOW),
                GravityRollEstimator.Status.MISSING_LOCATION,
                "Location unavailable");
        assertFailure(
                GravityRollEstimator.derive(
                        pointing, location, null,
                        J2000_NOON_SECONDS, 0, NOW),
                GravityRollEstimator.Status.MISSING_GRAVITY,
                "Camera tilt unavailable");

        CameraGravityWindow moving = new CameraGravityWindow();
        for (int index = 0; index < 6; index++) {
            moving.addSample(
                    0,
                    index % 2 == 0 ? 990 : 1_010,
                    0,
                    NOW - CameraGravityWindow.MIN_WINDOW_NANOS
                            + index * SAMPLE_PERIOD_NANOS);
        }
        assertFailure(
                GravityRollEstimator.derive(
                        pointing, location, moving.estimate(NOW),
                        J2000_NOON_SECONDS, 0, NOW),
                GravityRollEstimator.Status.UNSTABLE,
                "Hold camera still");

        CameraGravityWindow wrongTilt = stableGravity(0, 0, -1_000, NOW);
        assertFailure(
                GravityRollEstimator.derive(
                        pointing, location, wrongTilt.estimate(NOW),
                        J2000_NOON_SECONDS, 0, NOW),
                GravityRollEstimator.Status.SINGULAR,
                "Too close to vertical");
    }

    @Test
    public void locationAccuracyAndAgeLimitsAreInclusive() {
        ManualEquatorialPointing pointing = ManualEquatorialPointing.of(0.0, 0.0, NOW);
        CameraGravityWindow gravity = stableGravity(0, 707, -707, NOW);
        double longitude = longitudeForHourAngle(0.0);

        ObserverLocationFix boundary = ObserverLocationFix.of(
                45.0,
                longitude,
                ObserverLocationFix.MAX_ACCURACY_METERS,
                NOW - ObserverLocationFix.MAX_AGE_NANOS);
        assertEquals(
                GravityRollEstimator.Status.VALID,
                GravityRollEstimator.derive(
                        pointing, boundary, gravity.estimate(NOW),
                        J2000_NOON_SECONDS, 0, NOW).getStatus());

        ObserverLocationFix inaccurate = ObserverLocationFix.of(
                45.0,
                longitude,
                ObserverLocationFix.MAX_ACCURACY_METERS + Math.ulp(
                        ObserverLocationFix.MAX_ACCURACY_METERS),
                NOW);
        assertFailure(
                GravityRollEstimator.derive(
                        pointing, inaccurate, gravity.estimate(NOW),
                        J2000_NOON_SECONDS, 0, NOW),
                GravityRollEstimator.Status.MISSING_LOCATION,
                "Location unavailable");

        ObserverLocationFix stale = ObserverLocationFix.of(
                45.0,
                longitude,
                1.0,
                NOW - ObserverLocationFix.MAX_AGE_NANOS - 1L);
        assertFailure(
                GravityRollEstimator.derive(
                        pointing, stale, gravity.estimate(NOW),
                        J2000_NOON_SECONDS, 0, NOW),
                GravityRollEstimator.Status.MISSING_LOCATION,
                "Location unavailable");
    }

    @Test
    public void stalePointingAndGravityAreUnavailableAtExactLimits() {
        ObserverLocationFix location = locationAtHourAngle(45.0, 0.0, 0.0, NOW);
        ManualEquatorialPointing currentAtBoundary = ManualEquatorialPointing.of(
                0.0, 0.0, NOW - ManualEquatorialPointing.MAX_AGE_NANOS);
        CameraGravityWindow gravity = stableGravity(0, 707, -707, NOW);
        assertEquals(
                GravityRollEstimator.Status.VALID,
                GravityRollEstimator.derive(
                        currentAtBoundary, location, gravity.estimate(NOW),
                        J2000_NOON_SECONDS, 0, NOW).getStatus());

        ManualEquatorialPointing stale = ManualEquatorialPointing.of(
                0.0, 0.0, NOW - ManualEquatorialPointing.MAX_AGE_NANOS - 1L);
        assertFailure(
                GravityRollEstimator.derive(
                        stale, location, gravity.estimate(NOW),
                        J2000_NOON_SECONDS, 0, NOW),
                GravityRollEstimator.Status.MISSING_POINTING,
                "RA/Dec required");

        CameraGravityWindow oldGravity = stableGravity(
                0,
                707,
                -707,
                NOW - CameraGravityWindow.MAX_SAMPLE_AGE_NANOS - 1L);
        assertFailure(
                GravityRollEstimator.derive(
                        ManualEquatorialPointing.of(0.0, 0.0, NOW),
                        location,
                        oldGravity.estimate(NOW),
                        J2000_NOON_SECONDS,
                        0,
                        NOW),
                GravityRollEstimator.Status.MISSING_GRAVITY,
                "Camera tilt unavailable");

        assertFailure(
                GravityRollEstimator.derive(
                        ManualEquatorialPointing.of(0.0, 0.0, NOW + 1L),
                        location,
                        gravity.estimate(NOW),
                        J2000_NOON_SECONDS,
                        0,
                        NOW),
                GravityRollEstimator.Status.MISSING_POINTING,
                "RA/Dec required");
        assertFailure(
                GravityRollEstimator.derive(
                        ManualEquatorialPointing.of(0.0, 0.0, NOW),
                        ObserverLocationFix.of(
                                45.0, longitudeForHourAngle(0.0), 1.0, NOW + 1L),
                        gravity.estimate(NOW),
                        J2000_NOON_SECONDS,
                        0,
                        NOW),
                GravityRollEstimator.Status.MISSING_LOCATION,
                "Location unavailable");

        CameraGravityWindow.Estimate onceQualified = gravity.estimate(NOW);
        long later = NOW + CameraGravityWindow.MAX_SAMPLE_AGE_NANOS + 1L;
        assertFailure(
                GravityRollEstimator.derive(
                        ManualEquatorialPointing.of(0.0, 0.0, later),
                        ObserverLocationFix.of(
                                45.0, longitudeForHourAngle(0.0), 1.0, later),
                        onceQualified,
                        J2000_NOON_SECONDS,
                        0,
                        later),
                GravityRollEstimator.Status.MISSING_GRAVITY,
                "Camera tilt unavailable");
    }

    @Test
    public void manualPointingValidatesAndNormalizesRaDecWithoutRoll() {
        ManualEquatorialPointing parsed = ManualEquatorialPointing.parse(
                " 720.5 ", " -30.25 ", NOW);
        assertEquals(0.5, parsed.getRightAscensionDeg(), 0.0);
        assertEquals(-30.25, parsed.getDeclinationDeg(), 0.0);
        assertEquals(NOW, parsed.getAssertedElapsedRealtimeNanos());

        assertEquals(
                -180.0,
                ObserverLocationFix.of(0.0, 180.0, 1.0, NOW)
                        .getLongitudeEastDeg(),
                0.0);
        assertThrows(
                IllegalArgumentException.class,
                () -> ManualEquatorialPointing.parse("", "0", NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> ManualEquatorialPointing.parse("zero", "0", NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> ManualEquatorialPointing.of(Double.NaN, 0.0, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> ManualEquatorialPointing.of(0.0, 90.0001, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> ManualEquatorialPointing.of(0.0, 0.0, -1L));
        assertEquals(90.0, ManualEquatorialPointing.of(0.0, 90.0, NOW)
                .getDeclinationDeg(), 0.0);
        assertEquals(-90.0, ManualEquatorialPointing.of(0.0, -90.0, NOW)
                .getDeclinationDeg(), 0.0);
        assertThrows(
                IllegalArgumentException.class,
                () -> ObserverLocationFix.of(90.0001, 0.0, 1.0, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> ObserverLocationFix.of(0.0, 0.0, -1.0, NOW));
    }

    @Test
    public void invalidResultDoesNotExposeNumericFields() {
        GravityRollEstimator.Result result = GravityRollEstimator.derive(
                null, null, null, J2000_NOON_SECONDS, 0, NOW);
        assertFalse(result.isValid());
        assertThrows(IllegalStateException.class, result::getImageUpPositionAngleDeg);
        assertThrows(IllegalStateException.class, result::getAltitudeDeg);
        assertThrows(IllegalStateException.class, result::getProjection);
    }

    private static GravityRollEstimator.Result deriveAtHourAngle(
            double latitudeDeg,
            double declinationDeg,
            double hourAngleDeg,
            int downXMillig,
            int downYMillig,
            int downZMillig) {
        ManualEquatorialPointing pointing = ManualEquatorialPointing.of(0.0, declinationDeg, NOW);
        ObserverLocationFix location = locationAtHourAngle(
                latitudeDeg, 0.0, hourAngleDeg, NOW);
        CameraGravityWindow gravity = stableGravity(
                downXMillig, downYMillig, downZMillig, NOW);
        return GravityRollEstimator.derive(
                pointing,
                location,
                gravity.estimate(NOW),
                J2000_NOON_SECONDS,
                0,
                NOW);
    }

    private static ObserverLocationFix locationAtHourAngle(
            double latitudeDeg,
            double rightAscensionDeg,
            double hourAngleDeg,
            long receivedElapsedRealtimeNanos) {
        return ObserverLocationFix.of(
                latitudeDeg,
                longitudeForHourAngle(hourAngleDeg + rightAscensionDeg),
                1.0,
                receivedElapsedRealtimeNanos);
    }

    private static GravityRollEstimator.Result deriveWithExplicitRaLongitude(
            double latitudeDeg,
            double declinationDeg,
            double rightAscensionDeg,
            double longitudeEastDeg,
            int downXMillig,
            int downYMillig,
            int downZMillig) {
        ManualEquatorialPointing pointing = ManualEquatorialPointing.of(
                rightAscensionDeg, declinationDeg, NOW);
        ObserverLocationFix location = ObserverLocationFix.of(
                latitudeDeg, longitudeEastDeg, 1.0, NOW);
        CameraGravityWindow gravity = stableGravity(
                downXMillig, downYMillig, downZMillig, NOW);
        return GravityRollEstimator.derive(
                pointing,
                location,
                gravity.estimate(NOW),
                J2000_NOON_SECONDS,
                0,
                NOW);
    }

    private static double longitudeForHourAngle(double hourAngleDeg) {
        return hourAngleDeg - J2000_GMST_DEG;
    }

    private static CameraGravityWindow stableGravity(
            int x, int y, int z, long newestElapsedRealtimeNanos) {
        CameraGravityWindow gravity = new CameraGravityWindow();
        for (int index = 0; index < 6; index++) {
            gravity.addSample(
                    x,
                    y,
                    z,
                    newestElapsedRealtimeNanos - CameraGravityWindow.MIN_WINDOW_NANOS
                            + index * SAMPLE_PERIOD_NANOS);
        }
        return gravity;
    }

    private static void assertValidAngle(
            GravityRollEstimator.Result result, double expectedDeg, double toleranceDeg) {
        assertEquals(GravityRollEstimator.Status.VALID, result.getStatus());
        assertTrue(result.isValid());
        assertEquals("", result.getReason());
        assertEquals(expectedDeg, result.getImageUpPositionAngleDeg(), toleranceDeg);
    }

    private static void assertFailure(
            GravityRollEstimator.Result result,
            GravityRollEstimator.Status status,
            String reason) {
        assertEquals(status, result.getStatus());
        assertEquals(reason, result.getReason());
        assertFalse(result.isValid());
    }

    private static void assertCounts(
            SiderealRateCorrection correction,
            int expectedYaw,
            int expectedPitch,
            int expectedRoll) {
        assertEquals(expectedYaw, correction.getYawCounts());
        assertEquals(expectedPitch, correction.getPitchCounts());
        assertEquals(expectedRoll, correction.getRollCounts());
    }
}
