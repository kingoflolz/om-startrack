package dev.omshutter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ExposureIntentTrackerTest {
    @Test
    public void staleSnapshotCannotClearLocalInterlock() {
        ExposureIntentTracker tracker = new ExposureIntentTracker();
        tracker.reset(4L, true);
        tracker.begin();
        tracker.assign(6L);

        assertEquals(
                ExposureIntentTracker.Resolution.NONE,
                tracker.observe(5L, true));
        assertTrue(tracker.isAwaitingService());

        assertEquals(
                ExposureIntentTracker.Resolution.ACCEPTED,
                tracker.observe(6L, true));
        assertFalse(tracker.isAwaitingService());
    }

    @Test
    public void rejectionUnlocksAndCanBeRetried() {
        ExposureIntentTracker tracker = new ExposureIntentTracker();
        tracker.begin();
        tracker.assign(1L);

        assertEquals(
                ExposureIntentTracker.Resolution.REJECTED,
                tracker.observe(1L, false));
        assertFalse(tracker.isAwaitingService());

        tracker.begin();
        tracker.assign(2L);
        assertEquals(
                ExposureIntentTracker.Resolution.ACCEPTED,
                tracker.observe(2L, true));
        assertFalse(tracker.isAwaitingService());
    }

    @Test
    public void acceptedIntentCanLaterReportTransportFailureOnce() {
        ExposureIntentTracker tracker = new ExposureIntentTracker();
        tracker.begin();
        tracker.assign(9L);
        assertEquals(
                ExposureIntentTracker.Resolution.ACCEPTED,
                tracker.observe(9L, true));

        assertEquals(
                ExposureIntentTracker.Resolution.REJECTED,
                tracker.observe(9L, false));
        assertEquals(
                ExposureIntentTracker.Resolution.NONE,
                tracker.observe(9L, false));
    }

    @Test
    public void resetDoesNotReplayAnOldRejection() {
        ExposureIntentTracker tracker = new ExposureIntentTracker();
        tracker.reset(12L, false);

        assertEquals(
                ExposureIntentTracker.Resolution.NONE,
                tracker.observe(12L, false));
        assertFalse(tracker.isAwaitingService());
    }

    @Test
    public void rejectionPostedAfterLifecycleResetIsStillReported() {
        ExposureIntentTracker tracker = new ExposureIntentTracker();
        tracker.reset(4L, true);

        assertEquals(
                ExposureIntentTracker.Resolution.REJECTED,
                tracker.observe(5L, false));
        assertFalse(tracker.isAwaitingService());
    }
}
