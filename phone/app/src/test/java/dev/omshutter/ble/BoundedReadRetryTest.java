package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BoundedReadRetryTest {
    @Test
    public void backsOffWithoutExtendingOriginalDeadline() {
        BoundedReadRetry retry = new BoundedReadRetry(5_000L, 100L, 1_000L);

        assertEquals(100L, retry.nextDelay(1_000L));
        assertEquals(200L, retry.nextDelay(1_100L));
        assertEquals(400L, retry.nextDelay(1_300L));
        assertEquals(800L, retry.nextDelay(1_700L));
        assertEquals(1_000L, retry.nextDelay(2_500L));
        assertEquals(500L, retry.nextDelay(5_500L));
        assertEquals(-1L, retry.nextDelay(6_000L));
        assertTrue(retry.isActive());
    }

    @Test
    public void successResetUnwindsBusyAndStartsAFreshWindow() {
        BoundedReadRetry retry = new BoundedReadRetry(5_000L, 100L, 1_000L);
        retry.nextDelay(10_000L);
        assertTrue(retry.isActive());

        retry.reset();

        assertFalse(retry.isActive());
        assertEquals(100L, retry.nextDelay(30_000L));
        assertEquals(5_000L, retry.remaining(30_000L));
    }
}
