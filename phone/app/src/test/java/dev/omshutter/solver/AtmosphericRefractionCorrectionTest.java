package dev.omshutter.solver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import org.junit.Test;

public final class AtmosphericRefractionCorrectionTest {
    private static final AtmosphericRefractionCorrection.Atmosphere STANDARD =
            AtmosphericRefractionCorrection.Atmosphere.standard();
    private static final double EARTH = SiderealRateCorrection.SIDEREAL_RATE_RAD_PER_SECOND;
    private static final double PIXELS_PER_RADIAN = 300000.0 / 3.35;

    @Test
    public void standardAtmosphereHasExplicitOpticalConditionsAndReferenceCoefficients() {
        assertEquals(1013.25, STANDARD.getPressureHpa(), 0);
        assertEquals(15.0, STANDARD.getTemperatureC(), 0);
        assertEquals(0.5, STANDARD.getRelativeHumidity(), 0);
        assertEquals(0.55, STANDARD.getWavelengthMicrometers(), 0);
        assertEquals(0.00027719540684292506, STANDARD.getTanCoefficientRadians(), 1e-18);
        assertEquals(-3.1716950368124957e-7,
                STANDARD.getTanCubedCoefficientRadians(), 1e-20);
    }

    @Test
    public void pressureZeroIsExactIdentityIncludingContinuousFractions() {
        SiderealRateCorrection.BodyAngularVelocity original = body(1.123e-5, -2.456e-5, 3.789e-5);
        AtmosphericRefractionCorrection.Result result = AtmosphericRefractionCorrection.correct(
                original, zenith(30, 0.7),
                AtmosphericRefractionCorrection.Atmosphere.of(0, 15, 0.5, 0.55));
        assertTrue(result.isApplied());
        assertSame(original, result.getBodyAngularVelocity());
        assertEquals(result.getTrueAltitudeDeg(), result.getApparentAltitudeDeg(), 0);
    }

    @Test
    public void verticalMotionChangesByExpectedThirtySecondSubpixelAmounts() {
        // Zenith projects along body X; a body Y generator moves the center vertically.
        assertVerticalMotion(30, 0.214396410642, 98.5831124019);
        assertVerticalMotion(45, 0.108117381166, 57.0787093517);
    }

    @Test
    public void horizontalAndVerticalMotionUseDifferentRefractionScales() {
        double[] z = zenith(30, 0);
        double horizontal = AtmosphericRefractionCorrection.correct(body(EARTH, 0, 0), z,
                STANDARD).getBodyAngularVelocity().getXRadiansPerSecond();
        double vertical = AtmosphericRefractionCorrection.correct(body(0, EARTH, 0), z,
                STANDARD).getBodyAngularVelocity().getYRadiansPerSecond();
        assertEquals(0.9997239444577314, horizontal / EARTH, 1e-14);
        assertEquals(0.9989056246588865, vertical / EARTH, 1e-14);
        assertTrue(horizontal > vertical);
    }

    @Test
    public void zenithLimitShrinksTranslationsButLeavesRollUnchanged() {
        SiderealRateCorrection.BodyAngularVelocity input = body(EARTH, -EARTH / 2, EARTH / 3);
        double scale = 1 / (1 + STANDARD.getTanCoefficientRadians());
        double[] expected = {scale * EARTH, -scale * EARTH / 2, EARTH / 3};
        assertArrayEquals(expected, AtmosphericRefractionCorrection.correct(input,
                new double[]{0, 0, 1}, STANDARD).getBodyAngularVelocity().toArray(), 1e-18);
        for (double bearing : new double[]{0, 0.3, 1.7, 4.0}) {
            assertArrayEquals(expected, AtmosphericRefractionCorrection.correct(input,
                    zenith(90 - 1e-10, bearing), STANDARD)
                    .getBodyAngularVelocity().toArray(), 1e-16);
        }
    }

    @Test
    public void localRollIncludesApparentOrientationOnce() {
        double[] z = zenith(30, 0);
        AtmosphericRefractionCorrection.Result result = AtmosphericRefractionCorrection.correct(
                body(EARTH, 0, 0), z, STANDARD);
        double refraction = Math.toRadians(result.getApparentAltitudeDeg()
                - result.getTrueAltitudeDeg());
        double roll = result.getBodyAngularVelocity().getZRadiansPerSecond();
        // First order is R*omega_towardZenith, not zero and not twice that value.
        assertEquals(refraction * EARTH, roll, Math.abs(refraction * EARTH) * 0.002);
        assertTrue(roll > 0);
        double oppositeRoll = AtmosphericRefractionCorrection.correct(body(-EARTH, 0, 0),
                z, STANDARD).getBodyAngularVelocity().getZRadiansPerSecond();
        assertEquals(-roll, oppositeRoll, 0);
    }

    @Test
    public void rotationAboutZenithRemainsAnExactRigidRotation() {
        // An axisymmetric refraction map commutes with rotation around its zenith axis.
        double altitude = 35, angle = 0.81;
        double[] z = zenith(altitude, angle);
        AtmosphericRefractionCorrection.Result result = AtmosphericRefractionCorrection.correct(
                body(EARTH * z[0], EARTH * z[1], EARTH * z[2]), z, STANDARD);
        double[] apparentZenith = zenith(result.getApparentAltitudeDeg(), angle);
        assertArrayEquals(scale(apparentZenith, EARTH),
                result.getBodyAngularVelocity().toArray(), 2e-18);
    }

    @Test
    public void imageVelocityAndLocalRollMatchIndependentSphericalFiniteDifferences() {
        for (double altitude : new double[]{15, 30, 45, 70, 89.5}) {
            for (double bearing : new double[]{0, 0.7, 2.2}) {
                double[] z = zenith(altitude, bearing);
                double[] omega = {0.53 * EARTH, -0.71 * EARTH, 0.37 * EARTH};
                double[] actual = AtmosphericRefractionCorrection.correct(
                        body(omega[0], omega[1], omega[2]), z, STANDARD)
                        .getBodyAngularVelocity().toArray();
                assertArrayEquals("altitude=" + altitude + " bearing=" + bearing,
                        finiteDifferenceApparentGenerator(omega, z), actual, 2e-12);
            }
        }
    }

    @Test
    public void displacedSensorUsesFrozenAtmosphereAndMatchesFullImageField() {
        double[] omega = {0.53 * EARTH, -0.71 * EARTH, 0.37 * EARTH};
        for (double altitude : new double[]{15, 30, 45, 70, 89.5}) {
            for (double bearing : new double[]{0.3, 1.8}) {
                double[] z = zenith(altitude, bearing);
                for (double[] offset : new double[][]{{0.009, -0.006}, {-0.01, 0.01}}) {
                    double[] actual = AtmosphericRefractionCorrection.correctAtGnomonic(
                            body(omega[0], omega[1], omega[2]), z, STANDARD, offset[0], offset[1])
                            .getBodyAngularVelocity().toArray();
                    assertArrayEquals("altitude=" + altitude + " bearing=" + bearing,
                            finiteDifferenceApparentGenerator(omega, z, offset[0], offset[1]),
                            actual, 2e-12);
                }
            }
        }
    }

    @Test
    public void displacedPressureZeroMatchesOriginalGnomonicRateAndRoll() {
        double x = 0.4 * EARTH, y = -0.7 * EARTH, z = 0.2 * EARTH;
        double u = 0.012, v = -0.006;
        double[] expected = {x * (1 + v * v) - y * u * v - z * u,
                y * (1 + u * u) - x * u * v - z * v, z + (x * u + y * v) / 2};
        assertArrayEquals(expected, AtmosphericRefractionCorrection.correctAtGnomonic(
                body(x, y, z), zenith(30, 0.7),
                AtmosphericRefractionCorrection.Atmosphere.of(0, 15, 0.5, 0.55), u, v)
                .getBodyAngularVelocity().toArray(), 0);
    }

    @Test
    public void displacedBelowAltitudeGuardRetainsTotalGeometricAdjustment() {
        SiderealRateCorrection.BodyAngularVelocity original = body(0.4 * EARTH, -0.7 * EARTH,
                0.2 * EARTH);
        double[] zenith = zenith(8, 0.2);
        double u = 0.012, v = -0.006;
        AtmosphericRefractionCorrection.Result result = AtmosphericRefractionCorrection.correctAtGnomonic(
                original, zenith, STANDARD, u, v);
        assertFalse(result.isApplied());
        assertEquals(AtmosphericRefractionCorrection.Status.BELOW_MINIMUM_ALTITUDE,
                result.getStatus());
        assertArrayEquals(AtmosphericRefractionCorrection.correctAtGnomonic(original, zenith,
                AtmosphericRefractionCorrection.Atmosphere.of(0, 15, 0.5, 0.55), u, v)
                .getBodyAngularVelocity().toArray(), result.getBodyAngularVelocity().toArray(), 0);
        assertTrue(Math.abs(original.getXRadiansPerSecond()
                - result.getBodyAngularVelocity().getXRadiansPerSecond()) > 1e-8);
    }

    @Test
    public void displacedInputOutsideQualifiedRadiusIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AtmosphericRefractionCorrection.correctAtGnomonic(body(EARTH, 0, 0),
                        zenith(45, 0), STANDARD, 0.0201, 0));
        assertThrows(IllegalArgumentException.class,
                () -> AtmosphericRefractionCorrection.correctAtGnomonic(body(EARTH, 0, 0),
                        zenith(45, 0), STANDARD, 0, Double.NaN));
    }

    @Test
    public void displacedZeroOffsetIsExactOriginalCorrection() {
        SiderealRateCorrection.BodyAngularVelocity original = body(EARTH, -EARTH, EARTH);
        double[] zenith = zenith(30, 0.4);
        assertArrayEquals(AtmosphericRefractionCorrection.correct(original, zenith, STANDARD)
                .getBodyAngularVelocity().toArray(),
                AtmosphericRefractionCorrection.correctAtGnomonic(original, zenith, STANDARD, 0, 0)
                .getBodyAngularVelocity().toArray(), 0);
    }

    @Test
    public void displacedZenithLimitIsFiniteAndMatchesFullImageField() {
        double[] omega = {0.53 * EARTH, -0.71 * EARTH, 0.37 * EARTH};
        double[] zenith = {0, 0, 1};
        for (double distance : new double[]{1e-5, 9e-5, 1.1e-4, 0.002}) {
            double[] actual = AtmosphericRefractionCorrection.correctAtGnomonic(
                    body(omega[0], omega[1], omega[2]), zenith, STANDARD, distance, 0)
                    .getBodyAngularVelocity().toArray();
            assertArrayEquals(finiteDifferenceApparentGenerator(omega, zenith, distance, 0),
                    actual, 3e-12);
        }
    }

    @Test
    public void observerFixMayArriveAfterSolveCapture() {
        Instant time = Instant.parse("2026-09-12T04:00:00Z");
        ObserverCoordinateContext original = ObserverCoordinateContext.of(35, -122,
                time.getEpochSecond(), 0, 1_000_000_000L);
        ObserverCoordinateContext later = ObserverCoordinateContext.of(35, -122,
                time.getEpochSecond() + 60, 0, 61_000_000_000L);
        assertArrayEquals(AtmosphericRefractionCorrection.zenithBody(45, 20, 30, original,
                1_000_000_000L), AtmosphericRefractionCorrection.zenithBody(45, 20, 30, later,
                1_000_000_000L), 0);
    }

    @Test
    public void lowAltitudeIsExplicitlyUnchangedRatherThanExtrapolated() {
        SiderealRateCorrection.BodyAngularVelocity original = body(EARTH, 0, 0);
        assertTrue(AtmosphericRefractionCorrection.correct(original, zenith(10, 0), STANDARD)
                .isApplied());
        for (double altitude : new double[]{9.99, 0, -20, -90}) {
            AtmosphericRefractionCorrection.Result result = AtmosphericRefractionCorrection.correct(
                    original, zenith(altitude, 0), STANDARD);
            assertFalse(result.isApplied());
            assertEquals(AtmosphericRefractionCorrection.Status.BELOW_MINIMUM_ALTITUDE,
                    result.getStatus());
            assertSame(original, result.getBodyAngularVelocity());
            assertTrue(Double.isNaN(result.getApparentAltitudeDeg()));
        }
    }

    @Test
    public void observerTimeAndRightAscensionSelectCorrectHorizonAndCameraAxes() {
        Instant time = Instant.parse("2026-09-12T04:00:00Z");
        long monotonic = 1_000_000_000L;
        ObserverCoordinateContext observer = ObserverCoordinateContext.of(
                0, 0, time.getEpochSecond(), 0, monotonic);
        double meridianRa = Math.toDegrees(GravityRollEstimator.greenwichMeanSiderealTimeRadians(
                time.getEpochSecond(), 0));
        double[] east = AtmosphericRefractionCorrection.zenithBody(
                meridianRa + 45, 0, 0, observer, monotonic);
        double[] west = AtmosphericRefractionCorrection.zenithBody(
                meridianRa - 45, 0, 0, observer, monotonic);
        assertTrue(east[0] > 0.70 && west[0] < -0.70);
        assertEquals(Math.sqrt(0.5), east[2], 0.005);
        assertEquals(Math.sqrt(0.5), west[2], 0.005);
        double[] turned = AtmosphericRefractionCorrection.zenithBody(
                meridianRa + 45, 0, 90, observer, monotonic);
        assertArrayEquals(new double[]{-east[1], east[0], east[2]}, turned, 1e-15);
        double[] later = AtmosphericRefractionCorrection.zenithBody(
                meridianRa + 45, 0, 0, observer, monotonic + 60_000_000_000L);
        assertTrue(later[2] > east[2]); // Eastern target is rising.
        assertTrue(later[0] < east[0]);
    }

    @Test
    public void physicalRatesStayLinearAndNormalizeZenithInput() {
        double[] z = zenith(35, 0.7);
        double[] full = AtmosphericRefractionCorrection.correct(body(EARTH, -EARTH, EARTH),
                z, STANDARD).getBodyAngularVelocity().toArray();
        double[] half = AtmosphericRefractionCorrection.correct(
                body(EARTH / 2, -EARTH / 2, EARTH / 2), scale(z, 9), STANDARD)
                .getBodyAngularVelocity().toArray();
        assertArrayEquals(scale(full, 0.5), half, 1e-18);
        assertArrayEquals(new double[3], AtmosphericRefractionCorrection.correct(body(0, 0, 0),
                z, STANDARD).getBodyAngularVelocity().toArray(), 0);
    }

    @Test
    public void invalidWeatherOrDirectionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AtmosphericRefractionCorrection.Atmosphere.of(-1, 15, 0.5, 0.55));
        assertThrows(IllegalArgumentException.class,
                () -> AtmosphericRefractionCorrection.Atmosphere.of(1013, -90, 0.5, 0.55));
        assertThrows(IllegalArgumentException.class,
                () -> AtmosphericRefractionCorrection.Atmosphere.of(1013, 15, 1.1, 0.55));
        assertThrows(IllegalArgumentException.class,
                () -> AtmosphericRefractionCorrection.Atmosphere.of(1013, 15, 0.5, Double.NaN));
        for (double[] invalid : new double[][] {null, {1, 0}, {0, 0, 0}, {Double.NaN, 0, 1}}) {
            assertThrows(IllegalArgumentException.class, () -> AtmosphericRefractionCorrection.correct(
                    body(0, 0, 0), invalid, STANDARD));
        }
    }

    private static void assertVerticalMotion(double altitude, double expectedPixels,
            double expectedRefractionArcsec) {
        AtmosphericRefractionCorrection.Result result = AtmosphericRefractionCorrection.correct(
                body(0, EARTH, 0), zenith(altitude, 0), STANDARD);
        double delta = EARTH - result.getBodyAngularVelocity().getYRadiansPerSecond();
        assertEquals(expectedPixels, delta * 30 * PIXELS_PER_RADIAN, 1e-10);
        assertEquals(expectedRefractionArcsec,
                (result.getApparentAltitudeDeg() - altitude) * 3600, 1e-8);
    }

    private static SiderealRateCorrection.BodyAngularVelocity body(double x, double y, double z) {
        return SiderealRateCorrection.BodyAngularVelocity.of(x, y, z);
    }

    private static double[] zenith(double altitudeDeg, double bearing) {
        double altitude = Math.toRadians(altitudeDeg);
        return new double[]{Math.cos(altitude) * Math.cos(bearing),
                Math.cos(altitude) * Math.sin(bearing), Math.sin(altitude)};
    }

    /* Independent oracle: inverse-map each apparent ray, rotate it, differentiate the
     * spherical refraction map, then measure the velocity in a fixed gnomonic image.
     * The final image derivatives are numerical; they do not use the production roll formula.
     */
    private static double[] finiteDifferenceApparentGenerator(double[] omega, double[] zenith) {
        return finiteDifferenceApparentGenerator(omega, zenith, 0, 0);
    }

    private static double[] finiteDifferenceApparentGenerator(double[] omega, double[] zenith,
            double imageRight, double imageDown) {
        double t = Math.acos(zenith[2]);
        double lo = 0, hi = t;
        for (int i = 0; i < 64; i++) {
            double middle = (lo + hi) / 2;
            if (trueFromApparent(middle) > t) hi = middle; else lo = middle;
        }
        double bend = t - (lo + hi) / 2;
        double[] axis = t == 0 ? new double[]{1, 0, 0}
                : unit(cross(new double[]{0, 0, 1}, zenith));
        double[] forward = rotate(new double[]{0, 0, 1}, axis, bend);
        double[] right = rotate(new double[]{1, 0, 0}, axis, bend);
        double[] down = rotate(new double[]{0, 1, 0}, axis, bend);
        double[] center = imageVelocity(imageRight, imageDown, omega, zenith, forward, right, down);
        double step = 1e-6;
        double dyDx = (imageVelocity(imageRight + step, imageDown,
                omega, zenith, forward, right, down)[1]
                - imageVelocity(imageRight - step, imageDown,
                omega, zenith, forward, right, down)[1]) / (2 * step);
        double dxDy = (imageVelocity(imageRight, imageDown + step,
                omega, zenith, forward, right, down)[0]
                - imageVelocity(imageRight, imageDown - step,
                omega, zenith, forward, right, down)[0]) / (2 * step);
        return new double[]{-center[1], center[0], (dyDx - dxDy) / 2};
    }

    private static double[] imageVelocity(double x, double y, double[] omega, double[] zenith,
            double[] forward, double[] right, double[] down) {
        double[] apparent = unit(add(add(forward, scale(right, x)), scale(down, y)));
        double cosApparent = dot(apparent, zenith);
        double[] horizontal = unit(add(apparent, scale(zenith, -cosApparent)));
        double a = Math.acos(cosApparent), t = trueFromApparent(a);
        double[] truth = add(scale(zenith, Math.cos(t)), scale(horizontal, Math.sin(t)));
        double[] velocity = cross(omega, truth);
        double[] awayTrue = add(scale(horizontal, Math.cos(t)), scale(zenith, -Math.sin(t)));
        double[] azimuth = cross(zenith, horizontal);
        double[] awayApparent = add(scale(horizontal, Math.cos(a)), scale(zenith, -Math.sin(a)));
        double tangent = Math.tan(a);
        double derivative = 1 + (STANDARD.getTanCoefficientRadians()
                + 3 * STANDARD.getTanCubedCoefficientRadians() * tangent * tangent)
                * (1 + tangent * tangent);
        double[] apparentVelocity = add(scale(awayApparent, dot(velocity, awayTrue) / derivative),
                scale(azimuth, dot(velocity, azimuth) * Math.sin(a) / Math.sin(t)));
        double denominator = dot(apparent, forward);
        return new double[]{
                (dot(apparentVelocity, right) - x * dot(apparentVelocity, forward)) / denominator,
                (dot(apparentVelocity, down) - y * dot(apparentVelocity, forward)) / denominator};
    }

    private static double trueFromApparent(double angle) {
        double tangent = Math.tan(angle);
        return angle + STANDARD.getTanCoefficientRadians() * tangent
                + STANDARD.getTanCubedCoefficientRadians() * tangent * tangent * tangent;
    }

    private static double[] rotate(double[] v, double[] axis, double angle) {
        return add(add(scale(v, Math.cos(angle)), scale(cross(axis, v), Math.sin(angle))),
                scale(axis, dot(axis, v) * (1 - Math.cos(angle))));
    }

    private static double[] unit(double[] v) { return scale(v, 1 / Math.sqrt(dot(v, v))); }
    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
    private static double[] scale(double[] a, double s) {
        return new double[]{a[0] * s, a[1] * s, a[2] * s};
    }
    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }
    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }
}
