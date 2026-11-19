package dev.omshutter.plate;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import dev.omshutter.solver.PlateSolverSettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Solve-only timing coverage for the maximum-size Android STAR payload.
 *
 * <p>This is deliberately a regular instrumentation test instead of an AndroidX benchmark. It
 * exercises the exact production APK assets and JNI entry point, while emitting stable timing
 * fields that can be captured from {@code am instrument}. No latency threshold is asserted: the
 * correctness assertions remain useful across phones and Android power states.
 */
@RunWith(AndroidJUnit4.class)
public final class NativeTetra3DeviceBenchmarkTest {
    private static final String TAG = "Tetra3DeviceBenchmark";
    private static final String INDEX_ASSET = "tetra3/om1-300f4-to-45.t3ix";
    private static final String LENS_ASSET = "tetra3/om1-all-mft.t3lf";
    private static final int WARMUP_SOLVES = 3;
    private static final int MEASURED_SOLVES = 20;
    private static final double EXPECTED_RA_DEG = 0.0;
    private static final double EXPECTED_DEC_DEG = 61.04497562814016;
    private static final double EXPECTED_ROLL_DEG = 19.503100797579;
    private static final double EXPECTED_FOV_DEG = 3.266349613706033;
    private static final double ATTITUDE_TOLERANCE_DEG = 0.02;

    @Test
    public void benchmark256StarSolve() throws Exception {
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AssetManager assets = targetContext.getAssets();
        byte[] lensDatabase = readAsset(assets, LENS_ASSET);

        long handle = 0;
        long initStartedNs = SystemClock.elapsedRealtimeNanos();
        try {
            try (AssetFileDescriptor index = assets.openFd(INDEX_ASSET)) {
                handle = NativeTetra3Bridge.create(
                        index.getParcelFileDescriptor().getFd(),
                        index.getStartOffset(),
                        index.getLength(),
                        lensDatabase);
            }
            long initElapsedNs = SystemClock.elapsedRealtimeNanos() - initStartedNs;
            assertTrue("Native create returned the reserved null handle", handle != 0);

            long firstStartedNs = SystemClock.elapsedRealtimeNanos();
            NativeSolveResult first = solve300Mm(handle);
            long firstElapsedNs = SystemClock.elapsedRealtimeNanos() - firstStartedNs;
            assertFullFixtureSolution(first);

            for (int iteration = 0; iteration < WARMUP_SOLVES; iteration++) {
                assertFullFixtureSolution(solve300Mm(handle));
            }

            long[] warmElapsedNs = new long[MEASURED_SOLVES];
            NativeSolveResult last = null;
            for (int iteration = 0; iteration < MEASURED_SOLVES; iteration++) {
                long startedNs = SystemClock.elapsedRealtimeNanos();
                last = solve300Mm(handle);
                warmElapsedNs[iteration] = SystemClock.elapsedRealtimeNanos() - startedNs;
                assertFullFixtureSolution(last);
            }
            assertNotNull(last);

            Arrays.sort(warmElapsedNs);
            long medianNs = warmElapsedNs[warmElapsedNs.length / 2];
            int p95Index = (warmElapsedNs.length * 95 + 99) / 100 - 1;
            long p95Ns = warmElapsedNs[p95Index];
            long maximumNs = warmElapsedNs[warmElapsedNs.length - 1];
            Bundle report = new Bundle();
            report.putInt("tetra3_fixture_sources", NativeTetra3Android256Fixture.size());
            report.putInt("tetra3_warm_runs", MEASURED_SOLVES);
            report.putDouble("tetra3_engine_init_ms", milliseconds(initElapsedNs));
            report.putDouble("tetra3_first_solve_ms", milliseconds(firstElapsedNs));
            report.putDouble("tetra3_warm_median_ms", milliseconds(medianNs));
            report.putDouble("tetra3_warm_p95_ms", milliseconds(p95Ns));
            report.putDouble("tetra3_warm_max_ms", milliseconds(maximumNs));
            report.putInt(
                    "tetra3_final_matches",
                    last.getSolution().getPatternSourceIndices().length
                            + last.getSolution().getMatchedSourceIndices().length);
            InstrumentationRegistry.getInstrumentation().sendStatus(0, report);

            Log.i(TAG, String.format(
                    Locale.US,
                    "sources=%d init=%.3fms first=%.3fms warm[n=%d median=%.3fms "
                            + "p95=%.3fms max=%.3fms] matches=%d",
                    NativeTetra3Android256Fixture.size(),
                    milliseconds(initElapsedNs),
                    milliseconds(firstElapsedNs),
                    MEASURED_SOLVES,
                    milliseconds(medianNs),
                    milliseconds(p95Ns),
                    milliseconds(maximumNs),
                    last.getSolution().getPatternSourceIndices().length
                            + last.getSolution().getMatchedSourceIndices().length));
        } finally {
            NativeTetra3Bridge.close(handle);
        }
    }

    private static NativeSolveResult solve300Mm(long handle) {
        return NativeTetra3Bridge.solve(
                handle,
                "OLYMPUS M.300mm F4.0",
                300.0,
                NativeTetra3Android256Fixture.xy(),
                NativeTetra3Android256Fixture.flux());
    }

    private static void assertFullFixtureSolution(NativeSolveResult result) {
        assertEquals(NativeSolveResult.STATUS_SOLVED, result.getStatus());
        NativeSolution solution = result.getSolution();
        assertNotNull(solution);
        assertAngularDegreesNear(EXPECTED_RA_DEG, solution.getCenterRaDeg());
        assertEquals(
                EXPECTED_DEC_DEG,
                solution.getCenterDecDeg(),
                ATTITUDE_TOLERANCE_DEG);
        assertAngularDegreesNear(EXPECTED_ROLL_DEG, solution.getRollDeg());
        assertEquals(EXPECTED_FOV_DEG, solution.getHorizontalFovDeg(), 1.0e-6);

        int[] patternMatches = solution.getPatternSourceIndices();
        int[] validationMatches = solution.getMatchedSourceIndices();
        assertEquals("A tetra pattern must contain four matches", 4, patternMatches.length);
        assertEquals(
                "Every selected non-seed source must participate in final validation",
                PlateSolverSettings.DEFAULT_MAX_OBSERVATIONS - patternMatches.length,
                validationMatches.length);

        Set<Integer> observationIndices = new HashSet<>();
        assertMatchesSelectedPrefix(patternMatches, observationIndices);
        assertMatchesSelectedPrefix(validationMatches, observationIndices);
        assertEquals(
                "Pattern plus validation must cover the selected prefix",
                PlateSolverSettings.DEFAULT_MAX_OBSERVATIONS,
                observationIndices.size());
        assertTrue(Double.isFinite(solution.getRmsResidualArcsec()));
    }

    private static void assertMatchesSelectedPrefix(
            int[] matches, Set<Integer> observationIndices) {
        for (int observationIndex : matches) {
            assertTrue(
                    "Observation index is outside the fixture",
                    observationIndex >= 0
                            && observationIndex < PlateSolverSettings.DEFAULT_MAX_OBSERVATIONS);
            assertTrue(
                    "Observation index is duplicated",
                    observationIndices.add(observationIndex));
        }
    }

    private static void assertAngularDegreesNear(double expected, double actual) {
        assertTrue("Angle is not finite", Double.isFinite(actual));
        double delta = Math.abs(Math.IEEEremainder(actual - expected, 360.0));
        assertTrue(
                "Expected " + expected + " degrees, got " + actual,
                delta <= ATTITUDE_TOLERANCE_DEG);
    }

    private static double milliseconds(long nanoseconds) {
        return nanoseconds / 1_000_000.0;
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
