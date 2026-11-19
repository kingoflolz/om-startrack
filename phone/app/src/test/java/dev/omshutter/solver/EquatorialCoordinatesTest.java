package dev.omshutter.solver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EquatorialCoordinatesTest {
    private static final long J2000_NOON_SECONDS = 946_728_000L;
    private static final long REAL_DATE_SECONDS = 1_787_315_696L;
    private static final long NOW = 1_000_000_000_000L;

    @Test
    public void inverseMatchesExistingIndependentIauHorizonReference() {
        EquatorialCoordinates result = EquatorialCoordinates.fromHorizontal(
                -10.46723975975163, 107.68907485045, 37.7749, -122.4194,
                REAL_DATE_SECONDS, 0);
        assertEquals(123.456, result.getRightAscensionDeg(), 1.0e-10);
        assertEquals(-20.25, result.getDeclinationDeg(), 1.0e-10);
    }

    @Test
    public void equatorialRoundTripsAcrossDatesHemispheresAndRightAscensionWrap() {
        long[] dates = {0L, J2000_NOON_SECONDS, REAL_DATE_SECONDS,
                GravityRollEstimator.MAX_UTC_EPOCH_SECOND};
        double[][] locations = {{37.7749, -122.4194}, {-33.8688, 151.2093},
                {0.0, 0.0}, {75.0, 20.0}, {-90.0, -180.0}, {90.0, 180.0}};
        double[][] targets = {{0.0, -89.0}, {359.99999, -20.25},
                {-0.25, 0.0}, {720.25, 67.0}, {123.456, 42.0}};
        for (long date : dates) {
            for (double[] location : locations) {
                for (double[] target : targets) {
                    HorizontalCoordinates horizontal = HorizontalCoordinates.fromIcrs(
                            target[0], target[1], location[0], location[1], date, 123_456_789);
                    EquatorialCoordinates actual = EquatorialCoordinates.fromHorizontal(
                            horizontal.getAltitudeDeg(), horizontal.getAzimuthDeg(),
                            location[0], location[1], date, 123_456_789);
                    assertEquals(0.0, angularDifference(target[0], actual.getRightAscensionDeg()), 1.0e-9);
                    assertEquals(target[1], actual.getDeclinationDeg(), 1.0e-10);
                    assertTrue(actual.getRightAscensionDeg() >= 0.0);
                    assertTrue(actual.getRightAscensionDeg() < 360.0);
                }
            }
        }
    }

    @Test
    public void horizontalRoundTripsIncludeBelowHorizonAndWrappedAzimuth() {
        double[] altitudes = {-75.0, 0.0, 35.0, 89.0};
        double[] azimuths = {-1.0, 0.0, 90.0, 180.0, 721.0};
        for (double altitude : altitudes) {
            for (double azimuth : azimuths) {
                EquatorialCoordinates equatorial = EquatorialCoordinates.fromHorizontal(
                        altitude, azimuth, -33.8688, 151.2093, REAL_DATE_SECONDS, 987_654_321);
                HorizontalCoordinates actual = HorizontalCoordinates.fromIcrs(
                        equatorial.getRightAscensionDeg(), equatorial.getDeclinationDeg(),
                        -33.8688, 151.2093, REAL_DATE_SECONDS, 987_654_321);
                assertEquals(altitude, actual.getAltitudeDeg(), 1.0e-10);
                assertEquals(0.0, angularDifference(azimuth, actual.getAzimuthDeg()), 1.0e-9);
            }
        }
    }

    @Test
    public void stationaryHorizontalPointingAdvancesRightAscensionSidereally() {
        EquatorialCoordinates before = EquatorialCoordinates.fromHorizontal(
                35.0, 120.0, 37.7749, -122.4194, J2000_NOON_SECONDS, 0);
        EquatorialCoordinates after = EquatorialCoordinates.fromHorizontal(
                35.0, 120.0, 37.7749, -122.4194, J2000_NOON_SECONDS + 3_600L, 0);
        // One solar hour advances the mean sky by about 15.041 degrees, not 15 degrees.
        assertEquals(15.0410686,
                angularDifference(after.getRightAscensionDeg(), before.getRightAscensionDeg()), 1.0e-5);
        assertEquals(before.getDeclinationDeg(), after.getDeclinationDeg(), 1.0e-5);
    }

    @Test
    public void zenithAndNadirAcceptUndefinedAzimuthAndIgnoreFiniteAzimuth() {
        for (double altitude : new double[]{90.0, -90.0}) {
            EquatorialCoordinates vertical = EquatorialCoordinates.fromHorizontal(
                    altitude, Double.NaN, 30.0, -122.4194, REAL_DATE_SECONDS, 0);
            EquatorialCoordinates finiteAzimuth = EquatorialCoordinates.fromHorizontal(
                    altitude, 247.0, 30.0, -122.4194, REAL_DATE_SECONDS, 0);
            assertEquals(vertical.getRightAscensionDeg(), finiteAzimuth.getRightAscensionDeg(), 0.0);
            assertEquals(vertical.getDeclinationDeg(), finiteAzimuth.getDeclinationDeg(), 0.0);
            HorizontalCoordinates actual = HorizontalCoordinates.fromIcrs(
                    vertical.getRightAscensionDeg(), vertical.getDeclinationDeg(),
                    30.0, -122.4194, REAL_DATE_SECONDS, 0);
            assertEquals(altitude, actual.getAltitudeDeg(), 1.0e-10);
            assertTrue(Double.isNaN(actual.getAzimuthDeg()));
        }
    }

    @Test
    public void celestialPolesKeepDeclinationAndWithholdUndefinedRightAscension() {
        for (double declination : new double[]{90.0, -90.0}) {
            HorizontalCoordinates horizontal = HorizontalCoordinates.fromIcrs(
                    123.0, declination, -35.0, 23.0, REAL_DATE_SECONDS, 0);
            EquatorialCoordinates result = EquatorialCoordinates.fromHorizontal(
                    horizontal.getAltitudeDeg(), horizontal.getAzimuthDeg(),
                    -35.0, 23.0, REAL_DATE_SECONDS, 0);
            assertEquals(declination, result.getDeclinationDeg(), 1.0e-10);
            assertTrue(Double.isNaN(result.getRightAscensionDeg()));
        }
    }

    @Test
    public void observerContextUsesAdvancedTrustedUtcIncludingNanosecondCarry() {
        ObserverCoordinateContext context = ObserverCoordinateContext.of(
                -33.8688, 511.2093, REAL_DATE_SECONDS, 900_000_000, NOW);
        EquatorialCoordinates expected = EquatorialCoordinates.fromHorizontal(
                35.0, 120.0, -33.8688, 151.2093, REAL_DATE_SECONDS + 3L, 150_000_000);
        EquatorialCoordinates actual = context.equatorialAt(35.0, 120.0, NOW + 2_250_000_000L);
        assertEquals(expected.getRightAscensionDeg(), actual.getRightAscensionDeg(), 1.0e-12);
        assertEquals(expected.getDeclinationDeg(), actual.getDeclinationDeg(), 1.0e-12);
        assertThrows(IllegalArgumentException.class, () -> context.equatorialAt(35.0, 120.0, NOW - 1L));
        assertTrue(Double.isFinite(context.equatorialAt(90.0, Double.NaN, NOW).getDeclinationDeg()));
    }

    @Test
    public void observerContextRetainsActualFixTimeWithoutChangingUtcAnchor() {
        ObserverCoordinateContext cached = ObserverCoordinateContext.of(
                -33.8688, 151.2093, REAL_DATE_SECONDS, 0, NOW, NOW - 30_000_000_000L);
        assertEquals(NOW - 30_000_000_000L, cached.getLocationFixElapsedRealtimeNanos());
        assertEquals(REAL_DATE_SECONDS, cached.utcAt(NOW).getEpochSecond());
        ObserverCoordinateContext legacy = ObserverCoordinateContext.of(
                0.0, 0.0, REAL_DATE_SECONDS, 0, NOW);
        assertEquals(NOW, legacy.getLocationFixElapsedRealtimeNanos());
        assertThrows(IllegalArgumentException.class, () -> ObserverCoordinateContext.of(
                0.0, 0.0, REAL_DATE_SECONDS, 0, NOW, -1L));
        assertThrows(IllegalArgumentException.class, () -> ObserverCoordinateContext.of(
                0.0, 0.0, REAL_DATE_SECONDS, 0, NOW, NOW + 1L));
    }

    @Test
    public void invalidAnglesAndTimesAreRejected() {
        double[][] invalidAngles = {
            {Double.NaN, 0.0, 0.0, 0.0}, {Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0},
            {90.001, 0.0, 0.0, 0.0}, {-90.001, 0.0, 0.0, 0.0},
            {89.0, Double.NaN, 0.0, 0.0}, {90.0, Double.POSITIVE_INFINITY, 0.0, 0.0},
            {0.0, 0.0, Double.NaN, 0.0}, {0.0, 0.0, 90.001, 0.0},
            {0.0, 0.0, -90.001, 0.0}, {0.0, 0.0, 0.0, Double.NaN},
            {0.0, 0.0, 0.0, Double.NEGATIVE_INFINITY}
        };
        for (double[] angles : invalidAngles) {
            assertThrows(IllegalArgumentException.class, () -> EquatorialCoordinates.fromHorizontal(
                    angles[0], angles[1], angles[2], angles[3], REAL_DATE_SECONDS, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> EquatorialCoordinates.fromHorizontal(
                0.0, 0.0, 0.0, 0.0, -1L, 0));
        assertThrows(IllegalArgumentException.class, () -> EquatorialCoordinates.fromHorizontal(
                0.0, 0.0, 0.0, 0.0, GravityRollEstimator.MAX_UTC_EPOCH_SECOND + 1L, 0));
        assertThrows(IllegalArgumentException.class, () -> EquatorialCoordinates.fromHorizontal(
                0.0, 0.0, 0.0, 0.0, REAL_DATE_SECONDS, -1));
        assertThrows(IllegalArgumentException.class, () -> EquatorialCoordinates.fromHorizontal(
                0.0, 0.0, 0.0, 0.0, REAL_DATE_SECONDS, 1_000_000_000));
    }

    private static double angularDifference(double first, double second) {
        double wrapped = (first - second) % 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        if (wrapped > 180.0) wrapped -= 360.0;
        return wrapped;
    }
}
