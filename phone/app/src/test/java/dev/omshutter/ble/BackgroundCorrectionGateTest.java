package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BackgroundCorrectionGateTest {
    @Test
    public void duplicateHiddenEventsRequestOnlyOneExactZero() {
        BackgroundCorrectionGate gate = new BackgroundCorrectionGate();

        assertEquals(BackgroundCorrectionGate.Action.SUSPEND, gate.suspend());
        assertEquals(BackgroundCorrectionGate.Action.NONE, gate.suspend());
        assertTrue(gate.isSuspended());
    }

    @Test
    public void foregroundReturnResumesRetainedIntentOnce() {
        BackgroundCorrectionGate gate = new BackgroundCorrectionGate();
        gate.suspend();

        assertEquals(BackgroundCorrectionGate.Action.RESUME, gate.resume());
        assertEquals(BackgroundCorrectionGate.Action.NONE, gate.resume());
        assertFalse(gate.isSuspended());
    }

    @Test
    public void explicitStopClearsADeferredResume() {
        BackgroundCorrectionGate gate = new BackgroundCorrectionGate();
        gate.suspend();

        gate.clear();

        assertEquals(BackgroundCorrectionGate.Action.NONE, gate.resume());
    }
}
