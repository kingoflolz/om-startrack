package dev.omshutter.plate;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Device smoke coverage for APK asset packaging and the Rust/JNI ownership boundary. */
@RunWith(AndroidJUnit4.class)
public final class NativeTetra3DeviceSmokeTest {
    private static final String INDEX_ASSET = "tetra3/om1-300f4-to-45.t3ix";
    private static final double EXPECTED_RA_DEG = 0.0;
    private static final double EXPECTED_DEC_DEG = 61.04497562814016;
    private static final double EXPECTED_ROLL_DEG = 19.503100797579;
    private static final double EXPECTED_FOV_DEG = 3.266349613706033;
    private static final double ATTITUDE_TOLERANCE_DEG = 0.02;

    // validate-solves --android-star-pixels, exact 300 mm FOV, pointing 0/8 and
    // its first deterministic roll. These are raw integer STAR wire centroids;
    // the JNI boundary applies the firmware sampling-phase correction.
    private static final double[] STAR_XY = {
        599, 393, 498, 576, 498, 723, 455, 302, 627, 908, 904, 654, 542, 741,
        368, 370, 1100, 290, 988, 269, 767, 52, 1216, 912, 964, 611, 559, 541,
        432, 35, 38, 688, 562, 768, 1170, 313, 980, 364, 404, 864, 1196, 572,
        669, 644, 1247, 196, 881, 170, 1184, 529, 1101, 612, 1008, 504, 279,
        599, 132, 393, 1184, 151, 703, 96, 572, 203
    };
    private static final float[] STAR_FLUX = {
        1000000f, 794328f, 794328f, 794328f, 398107f, 316228f, 316228f,
        316228f, 251189f, 251189f, 251189f, 199526f, 199526f, 199526f,
        199526f, 158489f, 158489f, 158489f, 158489f, 125893f, 125893f,
        125893f, 125893f, 125893f, 100000f, 100000f, 100000f, 100000f,
        100000f, 100000f, 100000f, 100000f
    };
    @Test
    public void testPackagedIndexAndNativeEngineLifecycle() throws Exception {
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AssetManager assets = targetContext.getAssets();

        long handle = 0;
        try {
            // openFd() succeeds only for an uncompressed APK asset. Native create duplicates the
            // descriptor and maps precisely this asset region, so closing the AFD here is safe.
            try (AssetFileDescriptor index = assets.openFd(INDEX_ASSET)) {
                assertTrue("The packaged index length is unavailable", index.getLength() > 0);
                handle = NativeTetra3Bridge.create(
                        index.getParcelFileDescriptor().getFd(),
                        index.getStartOffset(),
                        index.getLength());
            }
            assertTrue("Native create returned the reserved null handle", handle != 0);

            // The target 300 mm lens has no database profile. Empty observations deliberately
            // stop before index search while still validating argument conversion, geometry,
            // fallback-lens preparation, result construction, and the native call boundary.
            NativeSolveResult result = NativeTetra3Bridge.solve(
                    handle,
                    "OLYMPUS M.300mm F4.0",
                    300.0,
                    new double[0],
                    new float[0]);

            assertEquals(NativeSolveResult.STATUS_NO_MATCH, result.getStatus());
            assertEquals(
                    NativeSolveResult.NO_MATCH_INSUFFICIENT_OBSERVATIONS,
                    result.getNoMatchReason());
            assertTrue(result.usedRectilinearFallback());
            assertNull(result.getSelectedProfileId());
            assertNull(result.getSolution());

            NativeSolveResult deadline = solve300Mm(
                    handle, STAR_XY, STAR_FLUX, 0L);
            assertEquals(NativeSolveResult.STATUS_NO_MATCH, deadline.getStatus());
            assertEquals(
                    NativeSolveResult.NO_MATCH_DEADLINE_EXCEEDED,
                    deadline.getNoMatchReason());
            assertNull(deadline.getSolution());

            NativeSolveResult solved = solve300Mm(handle, STAR_XY, STAR_FLUX);
            assertEquals(NativeSolveResult.STATUS_SOLVED, solved.getStatus());
            assertEquals(NativeSolveResult.NO_MATCH_NONE, solved.getNoMatchReason());
            assertTrue(solved.usedRectilinearFallback());
            assertNull(solved.getSelectedProfileId());

            NativeSolution solution = solved.getSolution();
            assertNotNull(solution);
            assertAngularDegreesNear(EXPECTED_RA_DEG, solution.getCenterRaDeg());
            assertEquals(
                    EXPECTED_DEC_DEG,
                    solution.getCenterDecDeg(),
                    ATTITUDE_TOLERANCE_DEG);
            assertAngularDegreesNear(EXPECTED_ROLL_DEG, solution.getRollDeg());
            assertEquals(EXPECTED_FOV_DEG, solution.getHorizontalFovDeg(), 1.0e-6);
            assertDisjointMatchEvidence(solution);
            assertTrue(Double.isFinite(solution.getRmsResidualArcsec()));
        } finally {
            NativeTetra3Bridge.close(handle);
            NativeTetra3Bridge.close(handle);
        }
    }

    private static NativeSolveResult solve300Mm(
            long handle, double[] xy, float[] flux) {
        return solve300Mm(
                handle,
                xy,
                flux,
                NativeTetra3Bridge.DEFAULT_SOLVE_TIMEOUT_MILLIS);
    }

    private static NativeSolveResult solve300Mm(
            long handle, double[] xy, float[] flux, long timeoutMillis) {
        return NativeTetra3Bridge.solve(
                handle,
                "OLYMPUS M.300mm F4.0",
                300.0,
                xy,
                flux,
                timeoutMillis);
    }

    private static void assertAngularDegreesNear(double expected, double actual) {
        assertTrue("Angle is not finite", Double.isFinite(actual));
        double delta = Math.abs(Math.IEEEremainder(actual - expected, 360.0));
        assertTrue(
                "Expected " + expected + " degrees, got " + actual,
                delta <= ATTITUDE_TOLERANCE_DEG);
    }

    private static void assertDisjointMatchEvidence(NativeSolution solution) {
        int[] patternMatches = solution.getPatternSourceIndices();
        int[] validationMatches = solution.getMatchedSourceIndices();
        assertEquals("A tetra pattern must contain four matches", 4, patternMatches.length);
        assertTrue(
                "Expected at least four independent validation matches",
                validationMatches.length >= 4);

        Set<Integer> observationIndices = new HashSet<>();
        for (int sourceIndex : patternMatches) {
            assertValidSourceIndex(sourceIndex);
            assertTrue("Pattern source index is duplicated", observationIndices.add(sourceIndex));
        }
        for (int sourceIndex : validationMatches) {
            assertValidSourceIndex(sourceIndex);
            assertTrue("Validation source overlaps the pattern", observationIndices.add(sourceIndex));
        }
    }

    private static void assertValidSourceIndex(int sourceIndex) {
        assertTrue(
                "Source index is outside the fixture",
                sourceIndex >= 0 && sourceIndex < STAR_FLUX.length);
    }

}
