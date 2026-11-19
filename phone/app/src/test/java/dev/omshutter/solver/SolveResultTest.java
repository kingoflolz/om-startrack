package dev.omshutter.solver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class SolveResultTest {
    @Test
    public void keepsPatternAndIndependentValidationAssociationsSeparate() {
        int[] patternSources = {7, 2, 9, 4};
        int[] validationSources = {1, 6};

        SolveResult result = result(patternSources, validationSources);
        patternSources[0] = 99;
        validationSources[0] = 99;

        assertArrayEquals(new int[]{7, 2, 9, 4}, result.getPatternSourceIndices());
        assertArrayEquals(new int[]{1, 6}, result.getMatchedSourceIndices());
        assertEquals(2, result.getMatchedStars());
    }

    @Test
    public void rejectsPatternValidationOverlap() {
        try {
            result(
                    new int[]{7, 2, 9, 4},
                    new int[]{4});
            fail("Expected overlapping source rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("overlap"));
        }
    }

    @Test
    public void preservesPhysicalFisheyeFovAbovePackagedIndexFov() {
        SolveResult result = new SolveResult(
                12.0,
                -15.0,
                5.0,
                131.584924,
                4.5,
                new int[]{0, 1, 2, 3},
                new int[0]);

        assertEquals(131.584924, result.getFovDeg(), 0.0);
    }

    @Test
    public void retainsSuppliedAndCentralSolvedFocalIndependentlyOfEdgeFov() {
        PlanarFocalCalibration calibration = PlanarFocalCalibration.of(300.0, 312.5);
        SolveResult result = new SolveResult(12, -15, 5, 3.2, 4.5,
                new int[]{0, 1, 2, 3}, new int[]{4}, calibration);
        assertTrue(result.hasFocalCalibration());
        assertSame(calibration, result.getFocalCalibration());
        assertEquals(300.0, result.getSuppliedFocalLengthMm(), 0);
        assertEquals(312.5, result.getSolvedFocalLengthMm(), 0);
        assertEquals(3.2, result.getFovDeg(), 0);
    }

    @Test
    public void legacyResultDoesNotInventFocalFromFov() {
        SolveResult result = result(new int[]{0, 1, 2, 3}, new int[]{4});
        assertFalse(result.hasFocalCalibration());
        assertNull(result.getFocalCalibration());
        assertTrue(Double.isNaN(result.getSuppliedFocalLengthMm()));
        assertTrue(Double.isNaN(result.getSolvedFocalLengthMm()));
    }

    private static SolveResult result(
            int[] patternSources,
            int[] validationSources) {
        return new SolveResult(
                12.0,
                -15.0,
                5.0,
                3.2,
                4.5,
                patternSources,
                validationSources);
    }
}
