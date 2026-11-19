package dev.omshutter.plate;

import static org.junit.Assert.*;

import org.junit.Test;

public class NativeSolveResultTest {
    @Test
    public void solvedWireResultDecodesWithoutLoadingNativeLibrary() {
        double[] metrics = {
                123.0, -45.0, 67.0, 3.266,
                Math.toRadians(2.0 / 3600.0), 312.5
        };
        NativeSolveResult result = new NativeSolveResult(
                NativeSolveResult.STATUS_SOLVED,
                NativeSolveResult.NO_MATCH_NONE,
                "",
                metrics,
                new int[]{0, 1, 2, 3},
                new int[]{4},
                new double[0]);

        assertTrue(result.usedRectilinearFallback());
        assertNull(result.getSelectedProfileId());
        assertEquals(123.0, result.getSolution().getCenterRaDeg(), 0.0);
        assertEquals(3.266, result.getSolution().getHorizontalFovDeg(), 0.0);
        assertEquals(2.0, result.getSolution().getRmsResidualArcsec(), 1e-12);
        assertEquals(312.5, result.getSolution().getSolvedFocalLengthMm(), 0.0);
        metrics[5] = 100.0;
        assertEquals(312.5, result.getSolution().getSolvedFocalLengthMm(), 0.0);
        assertArrayEquals(new int[]{0, 1, 2, 3},
                result.getSolution().getPatternSourceIndices());
        assertArrayEquals(new int[]{4}, result.getSolution().getMatchedSourceIndices());
    }

    @Test
    public void deadlineIsANormalNoMatchReason() {
        NativeSolveResult result = new NativeSolveResult(
                NativeSolveResult.STATUS_NO_MATCH,
                NativeSolveResult.NO_MATCH_DEADLINE_EXCEEDED,
                "",
                new double[0], new int[0], new int[0],
                new double[0]);

        assertEquals(NativeSolveResult.STATUS_NO_MATCH, result.getStatus());
        assertEquals(
                NativeSolveResult.NO_MATCH_DEADLINE_EXCEEDED,
                result.getNoMatchReason());
    }

    @Test
    public void selectedProfileIdAloneDeterminesLensSource() {
        NativeSolveResult result = new NativeSolveResult(
                NativeSolveResult.STATUS_NO_MATCH,
                NativeSolveResult.NO_MATCH_SEARCH_EXHAUSTED,
                "exact.profile",
                new double[0], new int[0], new int[0],
                new double[0]);
        assertFalse(result.usedRectilinearFallback());
        assertEquals("exact.profile", result.getSelectedProfileId());
    }

    @Test
    public void ambiguousWireResultKeepsOnlyTheTwoChanceProbabilities() {
        NativeSolveResult result = new NativeSolveResult(
                NativeSolveResult.STATUS_AMBIGUOUS,
                NativeSolveResult.NO_MATCH_NONE,
                "",
                new double[0],
                new int[0],
                new int[0],
                new double[]{1.0e-8, 2.0e-8});

        assertNull(result.getSolution());
        assertArrayEquals(
                new double[]{1.0e-8, 2.0e-8},
                result.getAmbiguityChanceProbabilities(),
                0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void patternAndValidationAssociationsMustBeDisjoint() {
        double[] metrics = {
                123.0, -45.0, 67.0, 3.266,
                0.00001, 300.0
        };
        new NativeSolveResult(
                NativeSolveResult.STATUS_SOLVED,
                NativeSolveResult.NO_MATCH_NONE,
                "",
                metrics,
                new int[]{0, 1, 2, 3},
                new int[]{0},
                new double[0]);
    }

    @Test
    public void oldMetricLayoutAndInvalidCentralFocalAreRejected() {
        for (double[] metrics : new double[][] {
                {123, -45, 67, 3.266, 0.00001},
                {123, -45, 67, 3.266, 0.00001, Double.NaN},
                {123, -45, 67, 3.266, 0.00001, 0.0},
                {123, -45, 67, 3.266, 0.00001, -300.0}}) {
            assertThrows(IllegalArgumentException.class, () -> new NativeSolveResult(
                    NativeSolveResult.STATUS_SOLVED, NativeSolveResult.NO_MATCH_NONE, "",
                    metrics, new int[]{0, 1, 2, 3}, new int[]{4}, new double[0]));
        }
    }
}
