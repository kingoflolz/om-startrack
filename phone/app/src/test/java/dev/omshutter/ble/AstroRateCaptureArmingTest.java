package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AstroRateCaptureArmingTest {
    @Test
    public void activeCaptureWaitsUntilRateAckBeforeControlMayStart() {
        AstroRateCaptureArming arming = new AstroRateCaptureArming();

        assertEquals(
                AstroRateCaptureArming.Decision.WAIT_FOR_ACTIVE_ACK,
                arming.begin(true));
        assertTrue(arming.isWaiting());
        assertEquals(
                AstroRateCaptureArming.Decision.START_WITH_RATE,
                arming.activeWriteAcknowledged());
        assertFalse(arming.isWaiting());
    }

    @Test
    public void acknowledgedStopDuringArmingStartsCaptureWithoutRate() {
        AstroRateCaptureArming arming = new AstroRateCaptureArming();
        arming.begin(true);

        assertEquals(
                AstroRateCaptureArming.Decision.START_WITHOUT_RATE,
                arming.zeroAcknowledged());
        assertFalse(arming.isWaiting());
        assertEquals(
                AstroRateCaptureArming.Decision.NONE,
                arming.activeWriteAcknowledged());
    }

    @Test
    public void provenUnappliedStopFailsCaptureForTerminalCleanup() {
        AstroRateCaptureArming arming = new AstroRateCaptureArming();
        arming.begin(true);

        assertEquals(
                AstroRateCaptureArming.Decision.FAIL_CAPTURE,
                arming.zeroProvenUnapplied());
        assertFalse(arming.isWaiting());
    }

    @Test
    public void inactiveCaptureNeedsNoRateAckAndResetFencesLateCallbacks() {
        AstroRateCaptureArming arming = new AstroRateCaptureArming();
        assertEquals(
                AstroRateCaptureArming.Decision.START_WITHOUT_RATE,
                arming.begin(false));

        arming.begin(true);
        arming.reset();
        assertEquals(
                AstroRateCaptureArming.Decision.NONE,
                arming.activeWriteAcknowledged());
        assertEquals(
                AstroRateCaptureArming.Decision.NONE,
                arming.zeroAcknowledged());
    }
}
