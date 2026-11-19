package dev.omshutter.solver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HorizontalCoordinatesTest {
    private static final long J2000_NOON_SECONDS = 946_728_000L;
    private static final long NOW = 1_000_000_000_000L;
    private static final double J2000_GMST_DEG = 280.4606224044844;

    @Test
    public void cardinalHorizonAndPoleVectorsUseNorthClockwiseAzimuth() {
        double longitude = -J2000_GMST_DEG;

        HorizontalCoordinates east = HorizontalCoordinates.fromIcrs(
                90.0, 0.0, 0.0, longitude, J2000_NOON_SECONDS, 0);
        assertEquals(0.0, east.getAltitudeDeg(), 1.0e-4);
        assertEquals(90.0, east.getAzimuthDeg(), 1.0e-4);

        HorizontalCoordinates west = HorizontalCoordinates.fromIcrs(
                270.0, 0.0, 0.0, longitude, J2000_NOON_SECONDS, 0);
        assertEquals(0.0, west.getAltitudeDeg(), 1.0e-4);
        assertEquals(270.0, west.getAzimuthDeg(), 1.0e-4);

        HorizontalCoordinates northPole = HorizontalCoordinates.fromIcrs(
                0.0, 90.0, 45.0, longitude, J2000_NOON_SECONDS, 0);
        assertEquals(45.0, northPole.getAltitudeDeg(), 1.0e-4);
        assertEquals(0.0, northPole.getAzimuthDeg(), 1.0e-4);
    }

    @Test
    public void realDateVectorMatchesIndependentIauHorizonConversion() {
        HorizontalCoordinates result = HorizontalCoordinates.fromIcrs(
                123.456,
                -20.25,
                37.7749,
                -122.4194,
                1_787_315_696L,
                0);

        // Independent IAU 2006 pmat06 + gmst06 evaluation for the same vector.
        assertEquals(-10.46723975975163, result.getAltitudeDeg(), 1.0e-10);
        assertEquals(107.68907485045, result.getAzimuthDeg(), 1.0e-9);
    }

    @Test
    public void zenithKeepsAltitudeButWithholdsUndefinedAzimuth() {
        double latitude = Math.toRadians(30.0);
        double localSidereal = GravityRollEstimator.greenwichMeanSiderealTimeRadians(
                J2000_NOON_SECONDS, 0);
        double[] zenithOfDate = {
            Math.cos(latitude) * Math.cos(localSidereal),
            Math.cos(latitude) * Math.sin(localSidereal),
            Math.sin(latitude)
        };
        double[][] precession = GravityRollEstimator.icrsToMeanEquatorOfDateMatrix(
                J2000_NOON_SECONDS, 0);
        // A rotation matrix's transpose maps the exact mean-of-date zenith back to ICRS.
        double[] zenithIcrs = {
            precession[0][0] * zenithOfDate[0]
                    + precession[1][0] * zenithOfDate[1]
                    + precession[2][0] * zenithOfDate[2],
            precession[0][1] * zenithOfDate[0]
                    + precession[1][1] * zenithOfDate[1]
                    + precession[2][1] * zenithOfDate[2],
            precession[0][2] * zenithOfDate[0]
                    + precession[1][2] * zenithOfDate[1]
                    + precession[2][2] * zenithOfDate[2]
        };
        HorizontalCoordinates result = HorizontalCoordinates.fromIcrs(
                Math.toDegrees(Math.atan2(zenithIcrs[1], zenithIcrs[0])),
                Math.toDegrees(Math.asin(zenithIcrs[2])),
                30.0,
                0.0,
                J2000_NOON_SECONDS,
                0);

        assertEquals(90.0, result.getAltitudeDeg(), 1.0e-9);
        assertTrue(Double.isNaN(result.getAzimuthDeg()));
    }

    @Test
    public void observerContextAdvancesTrustedUtcFromMonotonicTime() {
        ObserverCoordinateContext context = ObserverCoordinateContext.of(
                10.0, 20.0, 100L, 900_000_000, NOW);
        ObserverCoordinateContext.UtcInstant advanced = context.utcAt(
                NOW + 2_250_000_000L);
        assertEquals(103L, advanced.getEpochSecond());
        assertEquals(150_000_000, advanced.getNano());
        assertThrows(IllegalArgumentException.class, () -> context.utcAt(NOW - 1L));

        ObserverCoordinateContext overflow = ObserverCoordinateContext.of(
                0.0, 0.0, Long.MAX_VALUE, 0, NOW);
        assertThrows(ArithmeticException.class, () -> overflow.utcAt(
                NOW + 1_000_000_000L));
    }

    @Test
    public void captureUtcCanPrecedeObserverFixWithoutChangingForwardClockContract() {
        ObserverCoordinateContext context = ObserverCoordinateContext.of(
                10.0, 380.0, 100L, 100_000_000, NOW);
        ObserverCoordinateContext.UtcInstant capture = context.utcAtCaptureElapsed(
                NOW - 2_250_000_000L);
        assertEquals(97L, capture.getEpochSecond());
        assertEquals(850_000_000, capture.getNano());
        assertEquals(10.0, context.getLatitudeDeg(), 0.0);
        assertEquals(20.0, context.getLongitudeEastDeg(), 0.0);
        assertThrows(IllegalArgumentException.class, () -> context.utcAt(NOW - 1L));
        assertThrows(IllegalArgumentException.class, () -> context.utcAtCaptureElapsed(-1L));
    }

}
