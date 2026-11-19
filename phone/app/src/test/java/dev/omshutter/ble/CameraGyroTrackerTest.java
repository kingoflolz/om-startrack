package dev.omshutter.ble;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.omshutter.protocol.GyroAttitudeProtocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

public final class CameraGyroTrackerTest {
    private static final int CURRENT = GyroAttitudeProtocol.FLAG_CURRENT_VALID;
    private static final int CAPTURE = CURRENT | GyroAttitudeProtocol.FLAG_CAPTURE_VALID;
    private static final int PREPARATION = CAPTURE
            | GyroAttitudeProtocol.FLAG_CAPTURE_PREPARATION;
    private static final long SECOND = 1_000_000_000L;
    private static final int ONE_Q30 = 1 << 30;

    @Test
    public void repeatedTransportValueCannotKeepStoppedIntegratorFresh() throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        GyroAttitudeProtocol.Snapshot first = snapshot(CURRENT, 100, 0, 0);
        assertTrue(tracker.accept(first, SECOND));
        assertFalse(tracker.accept(snapshot(CURRENT, 100, 0, 0), 2 * SECOND));

        assertSame(first, tracker.getLatestSample().getSnapshot());
        assertEquals(SECOND, tracker.getLatestReceiptNanos());
        assertTrue(tracker.isFresh(SECOND + CameraGyroTracker.MAX_SAMPLE_AGE_NANOS));
        assertFalse(tracker.isFresh(SECOND + CameraGyroTracker.MAX_SAMPLE_AGE_NANOS + 1));
        assertFalse(tracker.isFresh(SECOND - 1));
    }

    @Test
    public void captureMatchesNextSequenceAndUsesReadMidpointThenCameraTicks() throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        assertTrue(tracker.beginCapture(7, snapshot(CURRENT, 1_000, 9, 0),
                SECOND, SECOND + 20_000_000L));
        tracker.accept(snapshot(PREPARATION, 1_250, 10, 1_200), SECOND + 150_000_000L);

        CameraGyroTracker.Capture capture = tracker.getCapture(7);
        assertNotNull(capture);
        assertEquals(7, capture.getSolveGeneration());
        assertEquals(10, capture.getSequence());
        assertEquals(1_200, capture.getTick());
        assertEquals(SECOND + 110_000_000L, capture.getElapsedRealtimeNanos());
        assertTrue(capture.isPreparation());
        assertNull(tracker.getCapture(8));
        assertArrayEquals(new double[]{1, 0, 0, 0}, capture.getQuaternion(), 0);
        double[] returnedQuaternion = capture.getQuaternion();
        returnedQuaternion[0] = 0;
        assertEquals(1, capture.getQuaternion()[0], 0);
    }

    @Test
    public void captureCanPublishAtDuplicateCurrentTickWithoutRefreshingFreshness() throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        assertTrue(tracker.beginCapture(7, snapshot(CURRENT, 1_000, 9, 0), SECOND, SECOND));
        assertFalse(tracker.accept(snapshot(CAPTURE, 1_000, 10, 1_000),
                SECOND + 10_000_000L));
        assertNotNull(tracker.getCapture(7));
        assertEquals(SECOND, tracker.getLatestReceiptNanos());
    }

    @Test
    public void newerLivePoseAndLaterCaptureDoNotReplaceAlreadyMatchedLatch() throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        tracker.beginCapture(7, snapshot(CURRENT, 1_000, 9, 0), SECOND, SECOND);
        tracker.accept(snapshot(CAPTURE, 1_200, 10, 1_100), SECOND + 100_000_000L);
        CameraGyroTracker.Capture original = tracker.confirmCapture(7,
                snapshot(CAPTURE, 1_200, 10, 1_100), true);

        tracker.accept(snapshot(CAPTURE, 1_400, 11, 1_300), SECOND + 200_000_000L);
        assertSame(original, tracker.getCapture(7));
        assertEquals(1_400, tracker.getLatestSample().getSnapshot().getTick());
        assertTrue(tracker.beginCapture(8, snapshot(CAPTURE, 1_400, 11, 1_300),
                SECOND + 200_000_000L, SECOND + 200_000_000L));
        assertNull(tracker.getCapture(7));
        assertEquals(1_100, original.getTick());
    }

    @Test
    public void pausedSourceWithholdsLivePoseButRetainsCaptureAssociation() throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        tracker.beginCapture(7, snapshot(CURRENT, 1_000, 9, 0), SECOND, SECOND);
        assertFalse(tracker.accept(snapshot(0, 1_050, 9, 0), SECOND + 50_000_000L));
        assertNull(tracker.getLatestSample());
        assertEquals(-1, tracker.getLatestReceiptNanos());
        tracker.accept(snapshot(CAPTURE, 1_200, 10, 1_100), SECOND + 100_000_000L);
        assertNotNull(tracker.getCapture(7));
    }

    @Test
    public void regressingTickInvalidatesAndCannotResurrectAtSameOrOlderTick() throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        tracker.beginCapture(7, snapshot(CURRENT, 1_000, 9, 0), SECOND, SECOND);
        assertFalse(tracker.accept(snapshot(CURRENT, 999, 9, 0), SECOND + 1));
        assertNull(tracker.getLatestSample());
        assertFalse(tracker.accept(snapshot(CAPTURE, 1_000, 10, 1_000), SECOND + 2));
        assertNull(tracker.getLatestSample());
        assertTrue(tracker.accept(snapshot(CAPTURE, 1_001, 10, 1_000), SECOND + 3));
        assertNull(tracker.getCapture(7));
    }

    @Test
    public void countersWrapWithoutBreakingCaptureAssociation() throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        tracker.beginCapture(7, snapshot(CURRENT, 0xffff_ff00L, 0xffff_ffffL, 0), SECOND, SECOND);
        tracker.accept(snapshot(CAPTURE, 0x100, 0, 0x80),
                SECOND + 300_000_000L);
        assertEquals(SECOND + 192_000_000L, tracker.getCapture(7).getElapsedRealtimeNanos());
        assertEquals(0, tracker.getCapture(7).getSequence());
        assertEquals(0x100, tracker.getLatestSample().getSnapshot().getTick());
    }

    @Test
    public void missingAnEntireCaptureRejectsAssociationPermanently() throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        tracker.beginCapture(7, snapshot(CURRENT, 1_000, 9, 0), SECOND, SECOND);
        tracker.accept(snapshot(CAPTURE, 1_400, 11, 1_300), SECOND + 200_000_000L);
        assertNull(tracker.getCapture(7));
        tracker.accept(snapshot(CAPTURE, 1_500, 10, 1_100), SECOND + 250_000_000L);
        assertNull(tracker.getCapture(7));
    }

    @Test
    public void latchBeforeBaselineOrAfterCurrentSampleIsRejected() throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        tracker.beginCapture(7, snapshot(CURRENT, 1_000, 9, 0), SECOND, SECOND);
        tracker.accept(snapshot(CAPTURE, 1_200, 10, 999), SECOND + 100_000_000L);
        assertNull(tracker.getCapture(7));

        tracker.clear();
        tracker.beginCapture(8, snapshot(CURRENT, 1_000, 9, 0), SECOND, SECOND);
        tracker.accept(snapshot(CAPTURE, 1_200, 10, 1_201), SECOND + 101_000_000L);
        assertNull(tracker.getCapture(8));
    }

    @Test
    public void captureMustArriveWithinMaximumDelayAndCameraTickRange() throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        tracker.beginCapture(7, snapshot(CURRENT, 1_000, 9, 0), SECOND, SECOND);
        tracker.accept(snapshot(CAPTURE, 361_002, 10, 361_001),
                SECOND + CameraGyroTracker.MAX_CAPTURE_DELAY_NANOS);
        assertNull(tracker.getCapture(7));

        tracker.clear();
        tracker.beginCapture(8, snapshot(CURRENT, 1_000, 9, 0), SECOND, SECOND);
        tracker.accept(snapshot(CAPTURE, 1_200, 10, 1_100),
                SECOND + CameraGyroTracker.MAX_CAPTURE_DELAY_NANOS + 1);
        assertNull(tracker.getCapture(8));
    }

    @Test
    public void staleOrSlowBaselineDoesNotArmCapture() throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        GyroAttitudeProtocol.Snapshot baseline = snapshot(CURRENT, 1_000, 9, 0);
        tracker.accept(baseline, SECOND);
        assertFalse(tracker.beginCapture(7, baseline, 2 * SECOND, 2 * SECOND));
        tracker.accept(snapshot(CAPTURE, 1_200, 10, 1_100), 2 * SECOND + 100_000_000L);
        assertNull(tracker.getCapture(7));

        tracker.clear();
        assertFalse(tracker.beginCapture(8, baseline, SECOND,
                SECOND + CameraGyroTracker.MAX_SAMPLE_AGE_NANOS + 1));
    }

    @Test
    public void lateCallbackCannotRewindStateOrInvalidatePendingCapture() throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        tracker.beginCapture(7, snapshot(CURRENT, 1_000, 9, 0), SECOND, SECOND);
        tracker.accept(snapshot(CURRENT, 1_100, 9, 0), SECOND + 100_000_000L);
        CameraGyroTracker.Sample latest = tracker.getLatestSample();
        assertFalse(tracker.accept(snapshot(0, 0, 0, 0), SECOND + 99_000_000L));
        assertSame(latest, tracker.getLatestSample());
        tracker.accept(snapshot(CAPTURE, 1_200, 10, 1_150), SECOND + 110_000_000L);
        assertNotNull(tracker.getCapture(7));
    }

    @Test
    public void clearFencesConnectionAndValidatesTimestampArguments() throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        GyroAttitudeProtocol.Snapshot baseline = snapshot(CURRENT, 1_000, 9, 0);
        tracker.beginCapture(7, baseline, SECOND, SECOND);
        tracker.clear();
        assertNull(tracker.getLatestSample());
        assertNull(tracker.getCapture(7));
        assertFalse(tracker.isFresh(SECOND));
        assertTrue(tracker.accept(baseline, 0));
        assertThrows(IllegalArgumentException.class, () -> tracker.accept(null, 0));
        assertThrows(IllegalArgumentException.class, () -> tracker.accept(baseline, -1));
        assertThrows(IllegalArgumentException.class,
                () -> tracker.beginCapture(8, baseline, 10, 9));
    }

    @Test
    public void cancelStopsLateAssociationWithoutDiscardingCurrentPose() throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        tracker.beginCapture(7, snapshot(CURRENT, 1_000, 9, 0), SECOND, SECOND);
        tracker.cancelPendingCapture();
        tracker.accept(snapshot(CAPTURE, 1_200, 10, 1_100), SECOND + 100_000_000L);
        assertNull(tracker.getCapture(7));
        assertTrue(tracker.isFresh(SECOND + 100_000_000L));

        tracker.beginCapture(8, snapshot(CAPTURE, 1_200, 10, 1_100),
                SECOND + 100_000_000L, SECOND + 100_000_000L);
        tracker.accept(snapshot(CAPTURE, 1_400, 11, 1_300), SECOND + 200_000_000L);
        CameraGyroTracker.Capture matched = tracker.getCapture(8);
        tracker.cancelPendingCapture();
        assertSame(matched, tracker.getCapture(8));
    }

    @Test
    public void terminalReadRejectsAnInterveningCaptureButConfirmedLatchRemainsImmutable()
            throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        tracker.beginCapture(7, snapshot(CURRENT, 1_000, 9, 0), SECOND, SECOND);
        GyroAttitudeProtocol.Snapshot first = snapshot(CAPTURE, 1_200, 10, 1_100);
        tracker.accept(first, SECOND + 100_000_000L);
        CameraGyroTracker.Capture matched = tracker.confirmCapture(7, first, true);
        assertNotNull(matched);
        GyroAttitudeProtocol.Snapshot next = snapshot(CAPTURE, 1_400, 11, 1_300);
        tracker.accept(next, SECOND + 200_000_000L);
        assertNull(tracker.confirmCapture(7, next, false));
        assertEquals(10, matched.getSequence());
    }

    @Test
    public void cachedPostDoneReadCanBeCompletedByLaterNotificationWithoutAnotherRead()
            throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        tracker.beginCapture(7, snapshot(CURRENT, 1_000, 9, 0), SECOND, SECOND);
        GyroAttitudeProtocol.Snapshot cached = snapshot(CURRENT, 1_100, 9, 0);
        tracker.accept(cached, SECOND + 100_000_000L);
        assertNull(tracker.confirmCapture(7, cached, true));

        GyroAttitudeProtocol.Snapshot published = snapshot(CAPTURE, 1_300, 10, 1_200);
        tracker.accept(published, SECOND + 200_000_000L);
        CameraGyroTracker.Capture confirmed = tracker.confirmCapture(7, published, false);
        assertNotNull(confirmed);
        assertEquals(10, confirmed.getSequence());
    }

    @Test
    public void notificationCannotConfirmBeforePostDoneReadOrUndoInterveningCapture()
            throws Exception {
        CameraGyroTracker tracker = new CameraGyroTracker();
        tracker.beginCapture(7, snapshot(CURRENT, 1_000, 9, 0), SECOND, SECOND);
        GyroAttitudeProtocol.Snapshot first = snapshot(CAPTURE, 1_200, 10, 1_100);
        tracker.accept(first, SECOND + 100_000_000L);
        assertNull(tracker.confirmCapture(7, first, false));
        GyroAttitudeProtocol.Snapshot intervening = snapshot(CAPTURE, 1_400, 11, 1_300);
        tracker.accept(intervening, SECOND + 200_000_000L);
        assertNull(tracker.confirmCapture(7, intervening, true));
        GyroAttitudeProtocol.Snapshot lateFirst = snapshot(CAPTURE, 1_500, 10, 1_100);
        tracker.accept(lateFirst, SECOND + 250_000_000L);
        assertNull(tracker.confirmCapture(7, lateFirst, false));
    }

    private static GyroAttitudeProtocol.Snapshot snapshot(int flags, long tick,
            long captureSequence, long captureTick) throws GyroAttitudeProtocol.ProtocolException {
        ByteBuffer bytes = ByteBuffer.allocate(GyroAttitudeProtocol.VALUE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        bytes.put((byte) GyroAttitudeProtocol.VERSION).put((byte) flags).putShort((short) 0);
        bytes.putInt((int) tick).putInt((int) captureSequence).putInt((int) captureTick);
        bytes.putInt(ONE_Q30).putInt(0).putInt(0).putInt(0);
        bytes.putInt(ONE_Q30).putInt(0).putInt(0).putInt(0);
        return GyroAttitudeProtocol.parse(bytes.array());
    }
}
