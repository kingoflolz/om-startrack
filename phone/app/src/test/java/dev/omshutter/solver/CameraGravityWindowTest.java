package dev.omshutter.solver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CameraGravityWindowTest {
    private static final long HALF_SECOND = CameraGravityWindow.MIN_WINDOW_NANOS;
    private static final long SAMPLE_PERIOD_NANOS = 100_000_000L;

    @Test
    public void exactMinimumWindowProducesQualifiedPhysicalDownMean() {
        CameraGravityWindow window = new CameraGravityWindow();
        addConstantSamples(window, 0, 1_000, 0, 1_000L);

        CameraGravityWindow.Estimate estimate =
                window.estimate(1_000L + HALF_SECOND);

        assertEquals(CameraGravityWindow.State.QUALIFIED, estimate.getState());
        assertTrue(estimate.isQualified());
        assertEquals(6, estimate.getSampleCount());
        assertEquals(0.0, estimate.getDownXMillig(), 0.0);
        assertEquals(1_000.0, estimate.getDownYMillig(), 0.0);
        assertEquals(0.0, estimate.getDownZMillig(), 0.0);
        assertEquals(1_000.0, estimate.getMeanNormMillig(), 0.0);
        assertEquals(0.0, estimate.getRmsResidualMillig(), 0.0);
    }

    @Test
    public void shorterWindowAndNowRelativeEvictionAreUnavailableAtExactBoundaries() {
        CameraGravityWindow shortWindow = new CameraGravityWindow();
        for (int index = 0; index < 5; index++) {
            shortWindow.addSample(
                    0, 1_000, 0,
                    index * SAMPLE_PERIOD_NANOS);
        }
        shortWindow.addSample(0, 1_000, 0, HALF_SECOND - 1L);
        assertEquals(
                CameraGravityWindow.State.UNAVAILABLE,
                shortWindow.estimate(HALF_SECOND - 1L).getState());

        CameraGravityWindow freshWindow = stableWindow(0, 1_000, 0, 0L);
        long exactOldestAgeBoundary = CameraGravityWindow.MAX_SAMPLE_AGE_NANOS;
        assertEquals(
                CameraGravityWindow.State.QUALIFIED,
                freshWindow.estimate(exactOldestAgeBoundary).getState());
        assertEquals(
                CameraGravityWindow.State.UNAVAILABLE,
                freshWindow.estimate(exactOldestAgeBoundary + 1L).getState());
        assertEquals(5, freshWindow.size());
    }

    @Test
    public void meanNormBoundsAreInclusive() {
        assertEquals(
                CameraGravityWindow.State.QUALIFIED,
                stableWindow(900, 0, 0, 0L).estimate(HALF_SECOND).getState());
        assertEquals(
                CameraGravityWindow.State.QUALIFIED,
                stableWindow(1_100, 0, 0, 0L).estimate(HALF_SECOND).getState());
        assertEquals(
                CameraGravityWindow.State.UNSTABLE,
                stableWindow(899, 0, 0, 0L).estimate(HALF_SECOND).getState());
        assertEquals(
                CameraGravityWindow.State.UNSTABLE,
                stableWindow(1_101, 0, 0, 0L).estimate(HALF_SECOND).getState());
    }

    @Test
    public void rmsMotionBoundIsInclusive() {
        CameraGravityWindow exact = new CameraGravityWindow();
        for (int index = 0; index < 6; index++) {
            exact.addSample(
                    index % 2 == 0 ? 995 : 1_005,
                    0,
                    0,
                    index * SAMPLE_PERIOD_NANOS);
        }
        CameraGravityWindow.Estimate exactEstimate = exact.estimate(HALF_SECOND);
        assertEquals(5.0, exactEstimate.getRmsResidualMillig(), 0.0);
        assertEquals(CameraGravityWindow.State.QUALIFIED, exactEstimate.getState());

        CameraGravityWindow moving = new CameraGravityWindow();
        for (int index = 0; index < 6; index++) {
            moving.addSample(
                    index % 2 == 0 ? 994 : 1_006,
                    0,
                    0,
                    index * SAMPLE_PERIOD_NANOS);
        }
        CameraGravityWindow.Estimate movingEstimate = moving.estimate(HALF_SECOND);
        assertEquals(6.0, movingEstimate.getRmsResidualMillig(), 0.0);
        assertEquals(CameraGravityWindow.State.UNSTABLE, movingEstimate.getState());
    }

    @Test
    public void windowIsBoundedAndClearRemovesPriorTransportEpoch() {
        CameraGravityWindow window = new CameraGravityWindow();
        for (int index = 0; index <= CameraGravityWindow.MAX_SAMPLES; index++) {
            window.addSample(0, 1_000, 0, 1_000L);
        }
        assertEquals(CameraGravityWindow.MAX_SAMPLES, window.size());

        window.clear();
        assertEquals(0, window.size());
        CameraGravityWindow.Estimate estimate = window.estimate(2_000L);
        assertEquals(CameraGravityWindow.State.UNAVAILABLE, estimate.getState());
        assertFalse(estimate.isQualified());
        assertEquals(0, estimate.getSampleCount());
    }

    @Test
    public void rejectsNegativeOrOutOfOrderMonotonicTimes() {
        CameraGravityWindow window = new CameraGravityWindow();
        assertThrows(
                IllegalArgumentException.class,
                () -> window.addSample(0, 1_000, 0, -1L));
        window.addSample(0, 1_000, 0, 10L);
        assertThrows(
                IllegalArgumentException.class,
                () -> window.addSample(0, 1_000, 0, 9L));
        assertThrows(IllegalArgumentException.class, () -> window.estimate(9L));
    }

    @Test
    public void rejectsEndpointOnlySparseAndTooFewSamples() {
        CameraGravityWindow endpoints = new CameraGravityWindow();
        endpoints.addSample(0, 1_000, 0, 0L);
        endpoints.addSample(0, 1_000, 0, HALF_SECOND);
        assertEquals(
                CameraGravityWindow.State.UNAVAILABLE,
                endpoints.estimate(HALF_SECOND).getState());

        CameraGravityWindow tooFew = new CameraGravityWindow();
        for (int index = 0; index < CameraGravityWindow.MIN_SAMPLES - 1; index++) {
            tooFew.addSample(
                    0, 1_000, 0,
                    index * SAMPLE_PERIOD_NANOS);
        }
        assertEquals(
                CameraGravityWindow.State.UNAVAILABLE,
                tooFew.estimate(
                        (CameraGravityWindow.MIN_SAMPLES - 2L)
                                * SAMPLE_PERIOD_NANOS).getState());

        CameraGravityWindow exactGap = new CameraGravityWindow();
        long[] exactTimes = {
            0L,
            100_000_000L,
            200_000_000L,
            200_000_000L + CameraGravityWindow.MAX_SAMPLE_GAP_NANOS,
            300_000_000L + CameraGravityWindow.MAX_SAMPLE_GAP_NANOS
        };
        for (long time : exactTimes) exactGap.addSample(0, 1_000, 0, time);
        assertEquals(
                CameraGravityWindow.State.QUALIFIED,
                exactGap.estimate(exactTimes[exactTimes.length - 1]).getState());

        CameraGravityWindow excessiveGap = new CameraGravityWindow();
        long[] excessiveTimes = {
            0L,
            100_000_000L,
            200_000_000L,
            200_000_001L + CameraGravityWindow.MAX_SAMPLE_GAP_NANOS,
            300_000_001L + CameraGravityWindow.MAX_SAMPLE_GAP_NANOS
        };
        for (long time : excessiveTimes) excessiveGap.addSample(0, 1_000, 0, time);
        assertEquals(
                CameraGravityWindow.State.UNAVAILABLE,
                excessiveGap.estimate(excessiveTimes[excessiveTimes.length - 1]).getState());
    }

    private static CameraGravityWindow stableWindow(
            int x, int y, int z, long firstElapsedRealtimeNanos) {
        CameraGravityWindow window = new CameraGravityWindow();
        addConstantSamples(window, x, y, z, firstElapsedRealtimeNanos);
        return window;
    }

    private static void addConstantSamples(
            CameraGravityWindow window,
            int x,
            int y,
            int z,
            long firstElapsedRealtimeNanos) {
        for (int index = 0; index < 6; index++) {
            window.addSample(
                    x,
                    y,
                    z,
                    firstElapsedRealtimeNanos
                            + index * SAMPLE_PERIOD_NANOS);
        }
    }
}
