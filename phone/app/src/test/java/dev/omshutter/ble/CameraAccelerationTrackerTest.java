package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import dev.omshutter.protocol.StarProtocol;
import dev.omshutter.solver.CameraGravityWindow;

import org.junit.Test;

public final class CameraAccelerationTrackerTest {
    private static final int VALID =
            StarProtocol.ACCELERATION_VALID_FLAGS;

    @Test
    public void validTenHertzGenerationsWithNormalJitterQualifyGravityWindow() {
        CameraAccelerationTracker tracker = new CameraAccelerationTracker();
        long[] receiptNanos = {
            0L, 97_000_000L, 205_000_000L,
            309_000_000L, 412_000_000L, 520_000_000L
        };
        for (int index = 0; index <= 5; index++) {
            long generation = index == 0 ? 0xffff_ffffL : index;
            tracker.accept(sample(VALID, generation, 0, 1_000, 0),
                    receiptNanos[index]);
        }

        assertEquals(6, tracker.gravitySampleCount());
        CameraGravityWindow.Estimate estimate =
                tracker.estimate(receiptNanos[receiptNanos.length - 1]);
        assertEquals(CameraGravityWindow.State.QUALIFIED, estimate.getState());
        assertEquals(0.0, estimate.getDownXMillig(), 0.0);
        assertEquals(1_000.0, estimate.getDownYMillig(), 0.0);
    }

    @Test
    public void duplicateGenerationRefreshesReceiptWithoutDoubleFeeding() {
        CameraAccelerationTracker tracker = new CameraAccelerationTracker();
        StarProtocol.Acceleration first = sample(VALID, 9L, 1, 999, -2);
        StarProtocol.Acceleration duplicate = sample(VALID, 9L, 1, 999, -2);
        tracker.accept(first, 100L);
        tracker.accept(duplicate, 200L);

        assertSame(duplicate, tracker.getLatestSample());
        assertEquals(200L, tracker.getLatestReceiptElapsedRealtimeNanos());
        assertEquals(1, tracker.gravitySampleCount());
    }

    @Test
    public void invalidFlagTransitionClearsQualifiedHistory() {
        CameraAccelerationTracker tracker = new CameraAccelerationTracker();
        for (int index = 0; index <= 5; index++) {
            tracker.accept(sample(VALID, index + 1L, 0, 1_000, 0),
                    index * 100_000_000L);
        }
        assertEquals(
                CameraGravityWindow.State.QUALIFIED,
                tracker.estimate(CameraGravityWindow.MIN_WINDOW_NANOS).getState());

        StarProtocol.Acceleration stale = sample(0x07, 2, 0, 1_000, 0);
        tracker.accept(stale, CameraGravityWindow.MIN_WINDOW_NANOS + 1L);
        assertSame(stale, tracker.getLatestSample());
        assertEquals(0, tracker.gravitySampleCount());
        assertEquals(
                CameraGravityWindow.State.UNAVAILABLE,
                tracker.estimate(CameraGravityWindow.MIN_WINDOW_NANOS + 1L).getState());
    }

    @Test
    public void clearFencesTransportEpochAndAllowsSameGenerationAgain() {
        CameraAccelerationTracker tracker = new CameraAccelerationTracker();
        tracker.accept(sample(VALID, 7, 0, 1_000, 0), 100L);
        tracker.clear();

        assertNull(tracker.getLatestSample());
        assertEquals(-1L, tracker.getLatestReceiptElapsedRealtimeNanos());
        assertEquals(0, tracker.gravitySampleCount());
        tracker.accept(sample(VALID, 7, 0, 1_000, 0), 200L);
        assertEquals(1, tracker.gravitySampleCount());
    }

    @Test
    public void estimateIndependentlyExpiresAnOldBleReceipt() {
        CameraAccelerationTracker tracker = new CameraAccelerationTracker();
        for (int index = 0; index <= 5; index++) {
            tracker.accept(sample(VALID, index + 1L, 0, 1_000, 0),
                    index * 100_000_000L);
        }
        long newest = CameraGravityWindow.MIN_WINDOW_NANOS;
        assertEquals(
                CameraGravityWindow.State.QUALIFIED,
                tracker.estimate(newest).getState());
        assertEquals(
                CameraGravityWindow.State.UNAVAILABLE,
                tracker.estimate(
                        newest + CameraGravityWindow.MAX_SAMPLE_AGE_NANOS + 1L).getState());
    }

    @Test
    public void olderReceiptCannotReplaceNewerState() {
        CameraAccelerationTracker tracker = new CameraAccelerationTracker();
        StarProtocol.Acceleration newer = sample(VALID, 1, 0, 1_000, 0);
        tracker.accept(newer, 2L);
        tracker.accept(sample(VALID, 2, 0, 999, 0), 1L);
        assertSame(newer, tracker.getLatestSample());
        assertEquals(2L, tracker.getLatestReceiptElapsedRealtimeNanos());
        assertEquals(1, tracker.gravitySampleCount());
        assertThrows(IllegalArgumentException.class,
                () -> tracker.accept(sample(VALID, 2, 0, 1_000, 0), -1L));
    }

    private static StarProtocol.Acceleration sample(
            int flags, long generation, int x, int y, int z) {
        return new StarProtocol.Acceleration(flags, generation, x, y, z);
    }
}
