package dev.omshutter.solver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CorrectionScaleTest {
    @Test
    public void mapsCanonicalPercentToFactor() {
        assertEquals(0.0, CorrectionScale.factor(0), 0.0);
        assertEquals(0.5, CorrectionScale.factor(50), 0.0);
        assertEquals(1.0, CorrectionScale.factor(100), 0.0);
        assertThrows(IllegalArgumentException.class, () -> CorrectionScale.factor(101));
    }

    @Test
    public void halfDetentHasExactDocumentedEdges() {
        assertEquals(44, CorrectionScale.detentOnRelease(44).getPercent());
        assertFalse(CorrectionScale.detentOnRelease(44).isSnapped());
        assertEquals(50, CorrectionScale.detentOnRelease(45).getPercent());
        assertTrue(CorrectionScale.detentOnRelease(45).isSnapped());
        assertEquals(50, CorrectionScale.detentOnRelease(50).getPercent());
        assertTrue(CorrectionScale.detentOnRelease(50).isSnapped());
        assertEquals(50, CorrectionScale.detentOnRelease(55).getPercent());
        assertTrue(CorrectionScale.detentOnRelease(55).isSnapped());
        assertEquals(56, CorrectionScale.detentOnRelease(56).getPercent());
    }
}
