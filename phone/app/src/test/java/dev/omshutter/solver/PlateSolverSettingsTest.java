package dev.omshutter.solver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PlateSolverSettingsTest {
    @Test
    public void defaultsUseTheCompleteOnlineSolverPopulation() {
        PlateSolverSettings defaults = PlateSolverSettings.defaults();

        assertSame(defaults, PlateSolverSettings.defaults());
        assertEquals(256, defaults.getMaxObservations());
        assertEquals(90, defaults.getSeedToleranceArcsec());
        assertEquals(75, defaults.getMatchToleranceArcsec());
        assertEquals(4, defaults.getRequiredIndependentMatches());
        assertEquals(1_000, defaults.getTimeoutMillis());
    }

    @Test
    public void withMethodsReturnValidatedImmutableCopies() {
        PlateSolverSettings original = PlateSolverSettings.defaults();
        PlateSolverSettings changed = original
                .withMaxObservations(64)
                .withSeedToleranceArcsec(120)
                .withMatchToleranceArcsec(45)
                .withRequiredIndependentMatches(8)
                .withTimeoutMillis(2_500);

        assertEquals(PlateSolverSettings.defaults(), original);
        assertNotEquals(original, changed);
        assertEquals(64, changed.getMaxObservations());
        assertEquals(120, changed.getSeedToleranceArcsec());
        assertEquals(45, changed.getMatchToleranceArcsec());
        assertEquals(8, changed.getRequiredIndependentMatches());
        assertEquals(2_500, changed.getTimeoutMillis());
    }

    @Test
    public void publicBoundsAndCrossFieldConstraintAreEnforced() {
        new PlateSolverSettings(8, 15, 300, 4, 100);
        new PlateSolverSettings(256, 300, 15, 16, 10_000);

        assertInvalid(7, 90, 75, 3, 1_000);
        assertInvalid(257, 90, 75, 4, 1_000);
        assertInvalid(256, 14, 75, 4, 1_000);
        assertInvalid(256, 90, 301, 4, 1_000);
        assertInvalid(256, 90, 75, 0, 1_000);
        assertInvalid(256, 90, 75, 17, 1_000);
        assertInvalid(8, 90, 75, 5, 1_000);
        assertInvalid(256, 90, 75, 4, 99);
        assertInvalid(256, 90, 75, 4, 10_001);
    }

    @Test
    public void solveRequestOwnsTheSettingsSnapshotSelectedAtConstruction() {
        PlateSolverSettings selected = PlateSolverSettings.defaults().withTimeoutMillis(2_000);
        SolveRequest request = new SolveRequest(
                1,
                45.0,
                "lens",
                java.util.Collections.emptyList(),
                selected);

        assertSame(selected, request.getSolverSettings());
        assertTrue(request.getSolverSettings().getTimeoutMillis() != 1_000);
    }

    private static void assertInvalid(
            int maxObservations,
            int seedToleranceArcsec,
            int matchToleranceArcsec,
            int requiredIndependentMatches,
            int timeoutMillis) {
        try {
            new PlateSolverSettings(
                    maxObservations,
                    seedToleranceArcsec,
                    matchToleranceArcsec,
                    requiredIndependentMatches,
                    timeoutMillis);
            throw new AssertionError("invalid plate-solver settings were accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(!expected.getMessage().isEmpty());
        }
    }
}
