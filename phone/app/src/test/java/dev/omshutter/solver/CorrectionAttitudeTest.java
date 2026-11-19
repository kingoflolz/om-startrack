package dev.omshutter.solver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class CorrectionAttitudeTest {
    @Test
    public void normalizesCalculatedAngles() {
        CorrectionAttitude attitude = CorrectionAttitude.of(-10, 45.5, 540);
        assertEquals(350.0, attitude.getRightAscensionDeg(), 0.0);
        assertEquals(45.5, attitude.getDeclinationDeg(), 0.0);
        assertEquals(-180.0, attitude.getRollDeg(), 0.0);
    }

    @Test
    public void rejectsNonfiniteAndOutOfRangeCalculatedAttitude() {
        assertThrows(IllegalArgumentException.class,
                () -> CorrectionAttitude.of(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> CorrectionAttitude.of(0, 90.01, 0));
    }

    @Test
    public void correctionProvenanceIsSolvedOrGravityDerived() {
        assertArrayEquals(
                new CorrectionSourceSelector.Source[]{
                    CorrectionSourceSelector.Source.SOLVED,
                    CorrectionSourceSelector.Source.MANUAL_GRAVITY
                },
                CorrectionSourceSelector.Source.values());
    }

}
