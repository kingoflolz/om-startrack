package dev.omshutter.plate;

import static org.junit.Assert.assertEquals;

import dev.omshutter.solver.PlateSolverSettings;

import org.junit.Test;

public final class NativeTetra3BridgeTest {
    @Test
    public void defaultDeadlineIsOneSecond() {
        assertEquals(1_000L, NativeTetra3Bridge.DEFAULT_SOLVE_TIMEOUT_MILLIS);
        assertEquals(
                PlateSolverSettings.DEFAULT_TIMEOUT_MILLIS,
                NativeTetra3Bridge.DEFAULT_SOLVE_TIMEOUT_MILLIS);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeDeadlineIsRejectedBeforeLoadingNativeLibrary() {
        NativeTetra3Bridge.solve(
                1L,
                "lens",
                300.0,
                new double[0],
                new float[0],
                -1L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullSettingsAreRejectedBeforeLoadingNativeLibrary() {
        NativeTetra3Bridge.solve(
                1L,
                "lens",
                300.0,
                new double[0],
                new float[0],
                (PlateSolverSettings) null);
    }
}
