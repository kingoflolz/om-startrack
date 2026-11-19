package dev.omshutter.plate;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Production-index integration coverage for the OM-1 8 mm equisolid fisheye. */
@RunWith(AndroidJUnit4.class)
public final class NativeTetra3WideFieldDeviceTest {
    private static final String INDEX_ASSET = "tetra3/om1-300f4-to-45.t3ix";
    private static final String LENS_ASSET = "tetra3/om1-all-mft.t3lf";
    private static final double ATTITUDE_TOLERANCE_DEG = 0.02;
    private static final double MAX_WIDE_RMS_ARCSEC = 300.0;
    private static final double MAX_WIDE_RESIDUAL_ARCSEC = 600.0;

    @Test
    public void testExact8MmProfileUsesLocalPatternAndFullFieldValidation() throws Exception {
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AssetManager assets = targetContext.getAssets();
        byte[] lensDatabase = readAsset(assets, LENS_ASSET);

        long handle = 0;
        try {
            try (AssetFileDescriptor index = assets.openFd(INDEX_ASSET)) {
                handle = NativeTetra3Bridge.create(
                        index.getParcelFileDescriptor().getFd(),
                        index.getStartOffset(),
                        index.getLength(),
                        lensDatabase);
            }
            assertTrue("Native create returned the reserved null handle", handle != 0);

            long startedNs = SystemClock.elapsedRealtimeNanos();
            NativeSolveResult result = solve8Mm(handle);
            long elapsedNs = SystemClock.elapsedRealtimeNanos() - startedNs;

            assertEquals(NativeSolveResult.STATUS_SOLVED, result.getStatus());
            assertEquals(NativeSolveResult.NO_MATCH_NONE, result.getNoMatchReason());
            assertTrue(!result.usedRectilinearFallback());
            assertEquals(
                    NativeTetra3Android8mm256Fixture.EXPECTED_PROFILE_ID,
                    result.getSelectedProfileId());

            NativeSolution solution = result.getSolution();
            assertNotNull(solution);
            assertAngularDegreesNear(
                    NativeTetra3Android8mm256Fixture.EXPECTED_RA_DEG,
                    solution.getCenterRaDeg());
            assertEquals(
                    NativeTetra3Android8mm256Fixture.EXPECTED_DEC_DEG,
                    solution.getCenterDecDeg(),
                    ATTITUDE_TOLERANCE_DEG);
            assertAngularDegreesNear(
                    NativeTetra3Android8mm256Fixture.EXPECTED_ROLL_DEG,
                    solution.getRollDeg());
            assertEquals(
                    NativeTetra3Android8mm256Fixture.EXPECTED_HORIZONTAL_FOV_DEG,
                    solution.getHorizontalFovDeg(),
                    1.0e-5);
            assertTrue(Double.isFinite(solution.getRmsResidualArcsec()));
            assertTrue(solution.getRmsResidualArcsec() <= MAX_WIDE_RMS_ARCSEC);

            assertFullFixtureAssociations(solution);
            assertLocalPatternAndDistantValidation(solution);

            Bundle report = new Bundle();
            report.putDouble("tetra3_wide_solve_ms", elapsedNs / 1_000_000.0);
            report.putInt(
                    "tetra3_wide_final_matches",
                    solution.getPatternSourceIndices().length
                            + solution.getMatchedSourceIndices().length);
            InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
        } finally {
            NativeTetra3Bridge.close(handle);
        }
    }

    private static NativeSolveResult solve8Mm(long handle) {
        return NativeTetra3Bridge.solve(
                handle,
                NativeTetra3Android8mm256Fixture.CAMERA_LENS_NAME,
                NativeTetra3Android8mm256Fixture.FOCAL_LENGTH_MM,
                NativeTetra3Android8mm256Fixture.xy(),
                NativeTetra3Android8mm256Fixture.flux(),
                NativeTetra3Bridge.DEFAULT_SOLVE_TIMEOUT_MILLIS);
    }

    private static void assertFullFixtureAssociations(NativeSolution solution) {
        int[] patternMatches = solution.getPatternSourceIndices();
        int[] validationMatches = solution.getMatchedSourceIndices();
        assertEquals(4, patternMatches.length);
        assertEquals(
                "Every non-seed source must participate in final validation",
                NativeTetra3Android8mm256Fixture.SOURCE_COUNT - patternMatches.length,
                validationMatches.length);

        Set<Integer> observationIndices = new HashSet<>();
        assertMatchesFixture(patternMatches, observationIndices);
        assertMatchesFixture(validationMatches, observationIndices);
        assertEquals(NativeTetra3Android8mm256Fixture.SOURCE_COUNT, observationIndices.size());
    }

    private static void assertMatchesFixture(
            int[] matches, Set<Integer> observationIndices) {
        for (int observationIndex : matches) {
            assertTrue(
                    "Observation index is outside the fixture",
                    observationIndex >= 0
                            && observationIndex < NativeTetra3Android8mm256Fixture.SOURCE_COUNT);
            assertTrue(
                    "Observation index is duplicated",
                    observationIndices.add(observationIndex));
        }
    }

    private static void assertLocalPatternAndDistantValidation(NativeSolution solution) {
        assertTrue(
                "Fixture must place the globally brightest four outside one indexed seed span",
                NativeTetra3Android8mm256Fixture.BRIGHTEST_FOUR_SPAN_DEG
                        > NativeTetra3Android8mm256Fixture.MAX_INDEXED_PATTERN_EDGE_DEG);

        int[] actualPatternIds = solution.getPatternSourceIndices();
        Arrays.sort(actualPatternIds);
        int[] expectedPatternIds = NativeTetra3Android8mm256Fixture
                .expectedPatternSourceIds()
                .clone();
        Arrays.sort(expectedPatternIds);
        assertTrue(
                "Solver did not advance to the expected fainter local pattern",
                Arrays.equals(expectedPatternIds, actualPatternIds));
        assertTrue(actualPatternIds[0] >= 4);

        Set<Integer> validationIds = new HashSet<>();
        for (int sourceIndex : solution.getMatchedSourceIndices()) {
            validationIds.add(sourceIndex);
        }
        int[] farValidationIds =
                NativeTetra3Android8mm256Fixture.expectedFarValidationSourceIds();
        assertTrue(
                "Fixture must contain distant held-out validation",
                farValidationIds.length > 0);
        for (int sourceId : farValidationIds) {
            assertTrue(
                    "Missing validation source outside every local seed span: " + sourceId,
                    validationIds.contains(sourceId));
        }
    }

    private static void assertAngularDegreesNear(double expected, double actual) {
        assertTrue("Angle is not finite", Double.isFinite(actual));
        double delta = Math.abs(Math.IEEEremainder(actual - expected, 360.0));
        assertTrue(
                "Expected " + expected + " degrees, got " + actual,
                delta <= ATTITUDE_TOLERANCE_DEG);
    }

    private static byte[] readAsset(AssetManager assets, String name) throws IOException {
        try (InputStream input = assets.open(name);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
