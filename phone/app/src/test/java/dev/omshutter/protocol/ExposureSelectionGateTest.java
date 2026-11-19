package dev.omshutter.protocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExposureSelectionGateTest {
    @Test
    public void delayedProgrammaticCallbackIsNotAUserChoice() {
        ExposureSelectionGate gate = new ExposureSelectionGate();
        ExposureCatalogs.Option programmed = shutter("1/4");
        gate.expectProgrammatic(ExposureCatalogs.Kind.SHUTTER, programmed);

        assertFalse(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER, shutter("1/4")));
        assertFalse(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER, shutter("1/8")));
    }

    @Test
    public void userArmSurvivesAnOlderProgrammaticCallback() {
        ExposureSelectionGate gate = new ExposureSelectionGate();
        gate.expectProgrammatic(ExposureCatalogs.Kind.SHUTTER, shutter("1/4"));
        gate.armUserSelection(ExposureCatalogs.Kind.SHUTTER);

        assertFalse(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER, shutter("1/4")));
        assertTrue(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER, shutter("1/8")));
        assertFalse(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER, shutter("1/8")));
    }

    @Test
    public void multipleAdapterSelectionsMayArriveBeforeTheUserChoice() {
        ExposureSelectionGate gate = new ExposureSelectionGate();
        gate.expectProgrammatic(ExposureCatalogs.Kind.SHUTTER, shutter("1/4"));
        gate.expectProgrammatic(ExposureCatalogs.Kind.SHUTTER, shutter("1/8"));
        gate.armUserSelection(ExposureCatalogs.Kind.SHUTTER);

        assertFalse(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER, shutter("1/4")));
        assertFalse(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER, shutter("1/8")));
        assertTrue(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER, shutter("1/15")));
    }

    @Test
    public void duplicateExpectationsSuppressDuplicateCallbacksAcrossUserArm() {
        ExposureSelectionGate gate = new ExposureSelectionGate();
        gate.expectProgrammatic(ExposureCatalogs.Kind.SHUTTER, shutter("1/4"));
        gate.expectProgrammatic(ExposureCatalogs.Kind.SHUTTER, shutter("1/4"));

        assertFalse(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER, shutter("1/4")));
        gate.armUserSelection(ExposureCatalogs.Kind.SHUTTER);
        assertFalse(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER, shutter("1/4")));
        assertTrue(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER, shutter("1/8")));
    }

    @Test
    public void populationEpochSuppressesUnenumeratedCallbacksWithoutConsumingUserArm() {
        ExposureSelectionGate gate = new ExposureSelectionGate();
        long epoch = gate.beginProgrammaticUpdate(ExposureCatalogs.Kind.SHUTTER);
        gate.armUserSelection(ExposureCatalogs.Kind.SHUTTER);

        assertFalse(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER, shutter("1/4")));
        gate.endProgrammaticUpdate(ExposureCatalogs.Kind.SHUTTER, epoch);
        assertTrue(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER, shutter("1/8")));
    }

    @Test
    public void staleEpochEndCannotReleaseNewerPopulation() {
        ExposureSelectionGate gate = new ExposureSelectionGate();
        long stale = gate.beginProgrammaticUpdate(ExposureCatalogs.Kind.SHUTTER);
        long current = gate.beginProgrammaticUpdate(ExposureCatalogs.Kind.SHUTTER);
        gate.endProgrammaticUpdate(ExposureCatalogs.Kind.SHUTTER, stale);
        gate.armUserSelection(ExposureCatalogs.Kind.SHUTTER);

        assertFalse(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER, shutter("1/4")));
        gate.endProgrammaticUpdate(ExposureCatalogs.Kind.SHUTTER, current);
        assertTrue(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER, shutter("1/8")));
    }

    @Test
    public void wrongKindCannotConsumeAUserArm() {
        ExposureSelectionGate gate = new ExposureSelectionGate();
        gate.armUserSelection(ExposureCatalogs.Kind.SHUTTER);

        assertFalse(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER,
                new ExposureCatalogs.Option(ExposureCatalogs.Kind.ISO, "1600")));
        assertTrue(gate.shouldAccept(
                ExposureCatalogs.Kind.SHUTTER, shutter("1/8")));
    }

    private static ExposureCatalogs.Option shutter(String label) {
        return new ExposureCatalogs.Option(ExposureCatalogs.Kind.SHUTTER, label);
    }
}
