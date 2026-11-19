package dev.omshutter.ble;

import static org.junit.Assert.*;
import org.junit.Test;

public final class IbisPrepositionCaptureTest {
    @Test public void fullPressRequiresSuccessfulHalfApplyAndSettleInOrder() {
        IbisPrepositionCapture capture = new IbisPrepositionCapture();
        assertFalse(capture.settleCompleted());
        assertTrue(capture.begin());
        assertFalse(capture.begin());
        assertFalse(capture.applyCompleted(true));
        assertTrue(capture.halfPressCompleted(true));
        assertFalse(capture.settleCompleted());
        assertTrue(capture.applyCompleted(true));
        assertTrue(capture.settleCompleted());
        assertFalse(capture.settleCompleted());
        assertTrue(capture.isPreparing());
        assertTrue(capture.fullPressCompleted(true));
        assertFalse(capture.isPreparing());
        assertFalse(capture.fullPressCompleted(true));
    }

    @Test public void cancellationDuringEveryStepRejectsLateCallbacks() {
        for (int step = 0; step < 4; ++step) {
            IbisPrepositionCapture capture = new IbisPrepositionCapture();
            capture.begin();
            if (step > 0) capture.halfPressCompleted(true);
            if (step > 1) capture.applyCompleted(true);
            if (step > 2) capture.settleCompleted();
            capture.cancel();
            assertFalse(capture.halfPressCompleted(true));
            assertFalse(capture.applyCompleted(true));
            assertFalse(capture.settleCompleted());
            assertFalse(capture.fullPressCompleted(true));
            assertFalse(capture.isPreparing());
        }
    }

    @Test public void rejectionNeverFallsThroughToFullPress() {
        IbisPrepositionCapture capture = new IbisPrepositionCapture();
        capture.begin();
        assertFalse(capture.halfPressCompleted(false));
        assertFalse(capture.applyCompleted(true));
        assertFalse(capture.settleCompleted());
        capture.begin();
        capture.halfPressCompleted(true);
        capture.applyCompleted(true);
        capture.settleCompleted();
        assertFalse(capture.fullPressCompleted(false));
        assertFalse(capture.isPreparing());
        capture.begin();
        capture.halfPressCompleted(true);
        assertFalse(capture.applyCompleted(false));
        assertFalse(capture.settleCompleted());
    }

    @Test public void shutterLabelsMustRepresentFixedFiniteExposure() {
        assertEquals(1.0 / 125, IbisPrepositionCapture.exposureSeconds("1/125"), 1e-10);
        assertEquals(1.0 / 125, IbisPrepositionCapture.exposureSeconds("125"), 1e-10);
        assertEquals(1.0 / 2.5, IbisPrepositionCapture.exposureSeconds("2.5"), 1e-10);
        assertEquals(2.5, IbisPrepositionCapture.exposureSeconds("2.5\""), 1e-10);
        assertEquals(30.0, IbisPrepositionCapture.exposureSeconds("30s"), 1e-10);
        for (String label : new String[]{null, "Bulb", "Live Time", "0", "1/0", "-1", "NaN", "Infinity"}) {
            assertTrue(Double.isNaN(IbisPrepositionCapture.exposureSeconds(label)));
        }
    }
}
