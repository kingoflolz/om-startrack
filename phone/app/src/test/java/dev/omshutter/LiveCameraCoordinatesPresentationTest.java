package dev.omshutter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.omshutter.solver.CameraAttitudeEstimator;
import dev.omshutter.solver.EquatorialCoordinates;
import dev.omshutter.solver.ObserverCoordinateContext;
import dev.omshutter.solver.SolveResult;

import org.junit.Test;

import java.util.Locale;

public final class LiveCameraCoordinatesPresentationTest {
    private static final long NOW = 10_000_000_000L;
    private static final long UTC = 1_789_200_000L;
    private static final double[] IDENTITY = {1.0, 0.0, 0.0, 0.0};

    @Test
    public void liveCoordinatesRecoverTheSolveThenAdvanceWithStationaryCamera() {
        CameraAttitudeEstimator estimator = anchored();
        ObserverCoordinateContext observer = observer();
        EquatorialCoordinates initial = MainActivity.liveEquatorial(
                estimator.estimate(NOW), observer, NOW);
        assertNotNull(initial);
        assertEquals(30.0, initial.getRightAscensionDeg(), 1e-8);
        assertEquals(20.0, initial.getDeclinationDeg(), 1e-8);

        long later = NOW + 3_600_000_000_000L;
        estimator.accept(2L, IDENTITY, later);
        EquatorialCoordinates advanced = MainActivity.liveEquatorial(
                estimator.estimate(later), observer, later);
        assertNotNull(advanced);
        assertEquals(15.041, advanced.getRightAscensionDeg() - 30.0, 0.1);
    }

    @Test
    public void staleOrTestOnlyAttitudeCannotMasqueradeAsLiveCelestialPointing() {
        CameraAttitudeEstimator estimator = anchored();
        CameraAttitudeEstimator.Estimate fresh = estimator.estimate(NOW);
        assertNull(MainActivity.liveEquatorial(fresh, null, NOW));
        assertNull(MainActivity.liveEquatorial(fresh, observer(), NOW - 1L));
        assertNotNull(MainActivity.liveEquatorial(fresh, observer(),
                NOW + CameraAttitudeEstimator.MAX_SAMPLE_AGE_NANOS));
        assertNull(MainActivity.liveEquatorial(fresh, observer(),
                NOW + CameraAttitudeEstimator.MAX_SAMPLE_AGE_NANOS + 1L));
        assertTrue(estimator.zeroForTesting(NOW));
        assertTrue(estimator.estimate(NOW).isAvailable());
        assertNull(MainActivity.liveEquatorial(estimator.estimate(NOW), observer(), NOW));
        estimator.pause();
        assertNull(MainActivity.liveEquatorial(estimator.estimate(NOW), observer(), NOW));
    }

    @Test
    public void relativeAgesUseWholeElapsedUnitsAndRejectUnknownOrFutureTimestamps() {
        assertEquals("—", age(-1L));
        assertEquals("—", PlateSolveFormatting.relativeAge(NOW + 1L, NOW, Locale.US));
        assertEquals("just now", age(0L));
        assertEquals("just now", age(999_999_999L));
        assertEquals("1 s ago", age(1_000_000_000L));
        assertEquals("59 s ago", age(59_999_999_999L));
        assertEquals("1 min ago", age(60_000_000_000L));
        assertEquals("59 min ago", age(3_599_999_999_999L));
        assertEquals("1 h ago", age(3_600_000_000_000L));
        assertEquals("1 d ago", age(86_400_000_000_000L));
    }

    private static String age(long elapsedNanos) {
        return elapsedNanos < 0L
                ? PlateSolveFormatting.relativeAge(-1L, NOW, Locale.US)
                : PlateSolveFormatting.relativeAge(NOW, NOW + elapsedNanos, Locale.US);
    }

    private static ObserverCoordinateContext observer() {
        return ObserverCoordinateContext.of(37.0, -122.0, UTC, 0, NOW);
    }

    private static CameraAttitudeEstimator anchored() {
        CameraAttitudeEstimator estimator = new CameraAttitudeEstimator();
        estimator.accept(1L, IDENTITY, NOW);
        SolveResult result = new SolveResult(30.0, 20.0, 1.0, 3.0, 1.0,
                new int[]{0, 1, 2, 3}, new int[]{4, 5});
        assertTrue(estimator.anchor(result, estimator.capture(0L, 1L, IDENTITY),
                37.0, -122.0, UTC, 0));
        return estimator;
    }
}
