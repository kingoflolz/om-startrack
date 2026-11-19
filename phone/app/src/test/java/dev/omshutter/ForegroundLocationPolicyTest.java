package dev.omshutter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;

public final class ForegroundLocationPolicyTest {
    @Test
    public void acceptsExactFreshnessAndAccuracyBoundaries() {
        long now = ForegroundLocationPolicy.MAX_AGE_NANOS + 1L;
        assertTrue(ForegroundLocationPolicy.isQualified(
                90.0,
                -180.0,
                true,
                ForegroundLocationPolicy.MAX_ACCURACY_METERS,
                1L,
                now,
                false));
    }

    @Test
    public void rejectsStaleInaccurateFutureAndInvalidFixes() {
        long now = ForegroundLocationPolicy.MAX_AGE_NANOS + 1L;
        assertFalse(ForegroundLocationPolicy.isQualified(
                0.0, 0.0, true, 1f, 0L, now, false));
        assertFalse(ForegroundLocationPolicy.isQualified(
                0.0, 0.0, true, 1f, now + 1L, now, false));
        assertFalse(ForegroundLocationPolicy.isQualified(
                0.0, 0.0, true, 1f, 1L, now + 1L, false));
        assertFalse(ForegroundLocationPolicy.isQualified(
                0.0, 0.0, true, 2_000.01f, 1L, now, false));
        assertFalse(ForegroundLocationPolicy.isQualified(
                0.0, 0.0, false, 1f, 1L, now, false));
        assertFalse(ForegroundLocationPolicy.isQualified(
                91.0, 0.0, true, 1f, 1L, now, false));
        assertFalse(ForegroundLocationPolicy.isQualified(
                0.0, 181.0, true, 1f, 1L, now, false));
        assertFalse(ForegroundLocationPolicy.isQualified(
                0.0, 0.0, true, 1f, 1L, now, true));
    }

    @Test
    public void advancesGpsUtcByMonotonicAge() {
        assertEquals(
                Instant.parse("2020-01-01T00:00:01.500Z"),
                ForegroundLocationPolicy.advanceLocationUtc(
                        Instant.parse("2020-01-01T00:00:00Z").toEpochMilli(),
                        1_000_000_000L,
                        2_500_000_000L));
        assertNull(ForegroundLocationPolicy.advanceLocationUtc(0L, 1L, 2L));
        assertNull(ForegroundLocationPolicy.advanceLocationUtc(1L, 2L, 1L));
    }
}
