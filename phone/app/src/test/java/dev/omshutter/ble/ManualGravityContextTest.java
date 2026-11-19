package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import dev.omshutter.solver.CameraGravityWindow;
import dev.omshutter.solver.ObserverLocationFix;

import org.junit.Test;

public final class ManualGravityContextTest {
    @Test
    public void utcAdvancesFromItsOwnMonotonicAnchorAcrossSecondBoundary() {
        ManualGravityContext context = ManualGravityContext.capture(
                "1", "2", 50L, null,
                1_000L, 900_000_000, 100L);
        ManualGravityContext.UtcInstant advanced = context.utcAt(100_000_200L);
        assertEquals(1_001L, advanced.getEpochSecond());
        assertEquals(100, advanced.getNano());
    }

    @Test
    public void pointingAssertionAndUtcAnchorAreIndependent() {
        ManualGravityContext context = ManualGravityContext.capture(
                " 12 ", " -34 ", 10L, null,
                20L, 30, 40L);
        assertEquals("12", context.getRightAscension());
        assertEquals("-34", context.getDeclination());
        ManualGravityContext.UtcInstant advanced = context.utcAt(50L);
        assertEquals(20L, advanced.getEpochSecond());
        assertEquals(40, advanced.getNano());
    }

    @Test
    public void invalidOrBackwardTimeFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> ManualGravityContext.capture(
                        "1", "2", -1L, null, 0L, 0, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> ManualGravityContext.capture(
                        "1", "2", 0L, null, 0L, 1_000_000_000, 0L));
        ManualGravityContext context = ManualGravityContext.capture(
                "1", "2", 0L, null, 0L, 0, 2L);
        assertThrows(IllegalArgumentException.class, () -> context.utcAt(1L));

        ManualGravityContext outOfRangeUtc = ManualGravityContext.capture(
                "1", "2", 0L, null, -1L, 0, 0L);
        ManualGravityContext.Derivation result = outOfRangeUtc.derive(null, 0L);
        assertFalse(result.isValid());
        assertEquals("Time unavailable", result.getFailure());
    }

    @Test
    public void derivationReportsCompactFailureWithoutUsingAnySolvedRoll() {
        CameraGravityWindow gravity = new CameraGravityWindow();
        gravity.addSample(0, 1_000, 0, 0L);
        gravity.addSample(0, 1_000, 0, CameraGravityWindow.MIN_WINDOW_NANOS);
        ManualGravityContext missingPointing = ManualGravityContext.capture(
                "", "0", 0L,
                ObserverLocationFix.of(0, 0, 1, 0L),
                0L, 0, 0L);
        ManualGravityContext.Derivation result = missingPointing.derive(
                gravity.estimate(CameraGravityWindow.MIN_WINDOW_NANOS),
                CameraGravityWindow.MIN_WINDOW_NANOS);
        assertFalse(result.isValid());
        assertEquals("RA/Dec required", result.getFailure());
    }
}
