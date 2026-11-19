package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StarInitializationReadRetryTest {
    @Test
    public void retriesOnlyUnavailableInitializationReads() {
        assertEquals(
                StarInitializationReadRetry.FailureDisposition.RETRY_INITIALIZATION,
                StarInitializationReadRetry.classifyFailure(0x0e, true, false));
        assertEquals(
                StarInitializationReadRetry.FailureDisposition.FAIL,
                StarInitializationReadRetry.classifyFailure(0x0e, false, false));
        assertEquals(
                StarInitializationReadRetry.FailureDisposition.FAIL,
                StarInitializationReadRetry.classifyFailure(0x0e, true, true));
        assertEquals(
                StarInitializationReadRetry.FailureDisposition.FAIL,
                StarInitializationReadRetry.classifyFailure(0x80, true, false));
    }

    @Test
    public void invalidHandleIsTerminalEvenDuringStartup() {
        assertEquals(
                StarInitializationReadRetry.FailureDisposition.TERMINAL_INVALID_HANDLE,
                StarInitializationReadRetry.classifyFailure(0x01, true, false));
        assertEquals(
                StarInitializationReadRetry.FailureDisposition.TERMINAL_INVALID_HANDLE,
                StarInitializationReadRetry.classifyFailure(0x01, false, true));
    }

    @Test
    public void callbackIsBoundedAndFencedByEpochAndConnection() {
        StarInitializationReadRetry retry =
                new StarInitializationReadRetry(1_000L, 100L, 400L);

        assertEquals(100L, retry.nextDelay(7, 10_000L));
        assertTrue(retry.shouldRun(7, 7, true));
        assertFalse(retry.shouldRun(7, 8, true));
        assertFalse(retry.shouldRun(7, 7, false));
        assertEquals(StarInitializationReadRetry.NO_RETRY, retry.nextDelay(8, 10_100L));

        assertEquals(200L, retry.nextDelay(7, 10_100L));
        assertEquals(400L, retry.nextDelay(7, 10_300L));
        assertEquals(300L, retry.nextDelay(7, 10_700L));
        assertEquals(StarInitializationReadRetry.NO_RETRY, retry.nextDelay(7, 11_000L));
    }

    @Test
    public void resetCancelsOldCallbackAndAllowsFreshEpoch() {
        StarInitializationReadRetry retry =
                new StarInitializationReadRetry(1_000L, 100L, 400L);
        retry.nextDelay(3, 1_000L);
        assertTrue(retry.isActive());

        retry.reset();

        assertFalse(retry.isActive());
        assertFalse(retry.shouldRun(3, 3, true));
        assertEquals(100L, retry.nextDelay(4, 5_000L));
        assertTrue(retry.shouldRun(4, 4, true));
    }

    @Test
    public void persistentInitializationFailureCannotOpenAnotherWindow() {
        StarInitializationReadRetry retry =
                new StarInitializationReadRetry(1_000L, 100L, 400L);

        // Catalogs and one fence endpoint may have succeeded before each attempt, but 0x0e
        // remains owned by the original initialization batch.
        assertEquals(
                StarInitializationReadRetry.FailureDisposition.RETRY_INITIALIZATION,
                StarInitializationReadRetry.classifyFailure(0x0e, true, false));
        assertEquals(100L, retry.nextDelay(9, 10_000L));
        assertEquals(200L, retry.nextDelay(9, 10_100L));
        assertEquals(400L, retry.nextDelay(9, 10_300L));
        assertEquals(300L, retry.nextDelay(9, 10_700L));
        assertEquals(StarInitializationReadRetry.NO_RETRY, retry.nextDelay(9, 11_000L));

        // Exhaustion is sticky until the service explicitly finishes or fails the batch. Merely
        // classifying another fence-read 0x0e cannot reset or replace the deadline.
        assertEquals(
                StarInitializationReadRetry.FailureDisposition.RETRY_INITIALIZATION,
                StarInitializationReadRetry.classifyFailure(0x0e, true, false));
        assertEquals(StarInitializationReadRetry.NO_RETRY, retry.nextDelay(9, 12_000L));
        assertTrue(retry.isActive());
    }
}
