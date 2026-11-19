package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ShutterRecoveryPowerWindowTest {
    @Test
    public void recoveryWindowIsBoundedAndDoesNotSlideOnEveryRetry() {
        ShutterRecoveryPowerWindow window = new ShutterRecoveryPowerWindow(120_000L);

        window.ensureStarted(true, 1_000L);
        assertTrue(window.isUrgent(true, 1_000L));
        assertEquals(60_000L, window.remainingMs(true, 61_000L));

        window.ensureStarted(true, 61_000L);
        assertFalse(window.isUrgent(true, 121_000L));
        assertEquals(0L, window.remainingMs(true, 121_000L));
    }

    @Test
    public void explicitRetryRestartsButSuccessfulReleaseClearsWindow() {
        ShutterRecoveryPowerWindow window = new ShutterRecoveryPowerWindow(120_000L);
        window.ensureStarted(true, 1_000L);
        assertFalse(window.isUrgent(true, 200_000L));

        window.restart(true, 200_000L);
        assertTrue(window.isUrgent(true, 200_000L));
        assertEquals(120_000L, window.remainingMs(true, 200_000L));

        window.clear();
        assertFalse(window.isUrgent(true, 200_000L));
    }

    @Test
    public void noRecoveryNeverStartsOrReportsUrgent() {
        ShutterRecoveryPowerWindow window = new ShutterRecoveryPowerWindow(120_000L);
        window.ensureStarted(false, 1_000L);
        window.restart(false, 2_000L);
        assertFalse(window.isUrgent(false, 2_000L));
    }

    @Test
    public void processRestoreKeepsTheOriginalEpisodeDeadline() {
        ShutterRecoveryPowerWindow window = new ShutterRecoveryPowerWindow(120_000L);

        window.restoreRemaining(true, 25_000L, 7_000L);
        assertEquals(25_000L, window.remainingMs(true, 7_000L));
        assertFalse(window.ensureStarted(true, 8_000L));
        assertFalse(window.isUrgent(true, 32_000L));
    }

    @Test
    public void expiredProcessRestoreCannotRestartWithoutExplicitRetry() {
        ShutterRecoveryPowerWindow window = new ShutterRecoveryPowerWindow(120_000L);

        window.restoreRemaining(true, -1L, 7_000L);
        assertFalse(window.isUrgent(true, 7_000L));
        assertFalse(window.ensureStarted(true, 8_000L));

        window.restart(true, 8_000L);
        assertTrue(window.isUrgent(true, 8_000L));
    }
}
