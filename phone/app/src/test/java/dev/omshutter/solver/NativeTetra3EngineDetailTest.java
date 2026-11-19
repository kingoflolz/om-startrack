package dev.omshutter.solver;

import static org.junit.Assert.assertEquals;

import dev.omshutter.plate.NativeSolveResult;

import org.junit.Test;
import java.lang.reflect.Constructor;
import java.util.Collections;

public final class NativeTetra3EngineDetailTest {
    @Test
    public void deadlineHasSpecificUserFacingDetail() {
        assertEquals(
                "solve deadline expired before a verified field was found",
                NativeTetra3Engine.noMatchReasonDetail(
                        NativeSolveResult.NO_MATCH_DEADLINE_EXCEEDED));
    }

    @Test
    public void calibrationDetailDistinguishesOptionalPriorFromProfileFreeFit() {
        assertEquals(
                "Plate solved with profile-free self-calibration",
                NativeTetra3Engine.calibrationDetail(null));
        assertEquals(
                "Plate solved with profile-assisted self-calibration lens.example",
                NativeTetra3Engine.calibrationDetail("lens.example"));
    }

    @Test
    public void adapterBindsNativeCentralFocalToTheExactSuppliedRequest() throws Exception {
        // Construct the JNI value without loading a native library or opening Android assets.
        Constructor<NativeSolveResult> constructor = NativeSolveResult.class.getDeclaredConstructor(
                int.class, int.class, String.class, double[].class, int[].class, int[].class,
                double[].class);
        constructor.setAccessible(true);
        NativeSolveResult nativeResult = constructor.newInstance(
                NativeSolveResult.STATUS_SOLVED, NativeSolveResult.NO_MATCH_NONE, "",
                new double[]{12, -15, 5, 3.2, 0.00001, 312.5},
                new int[]{0, 1, 2, 3}, new int[]{4}, new double[0]);
        SolveRequest request = new SolveRequest(1, 300, "", Collections.emptyList());
        SolveResult result = NativeTetra3Engine.adaptSolution(nativeResult.getSolution(), request);
        assertEquals(300, result.getSuppliedFocalLengthMm(), 0);
        assertEquals(312.5, result.getSolvedFocalLengthMm(), 0);
        assertEquals(1 / 0.96, result.getFocalCalibration().getScale(), 0);
        SolveRequest anotherRequest = new SolveRequest(2, 450, "", Collections.emptyList());
        assertEquals(450, NativeTetra3Engine.adaptSolution(nativeResult.getSolution(), anotherRequest)
                .getSuppliedFocalLengthMm(), 0);
    }
}
