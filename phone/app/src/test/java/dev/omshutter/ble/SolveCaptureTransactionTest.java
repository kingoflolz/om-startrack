package dev.omshutter.ble;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SolveCaptureTransactionTest {
    @Test
    public void solvedTransactionSurvivesTemporaryAdmissionFailure() {
        SolveCaptureTransaction transaction = new SolveCaptureTransaction();
        transaction.begin(true);
        transaction.bindGeneration(7L);
        transaction.solveSucceeded(7L);

        assertFalse(transaction.consumeIfAdmitted(
                false, false, -1L, -1L, -1L, -1L));
        // First wait models a terminal indication preceding its CONTROL write callback;
        // the second models the ordered restored-STATE read still owning GATT.
        assertFalse(transaction.consumeIfAdmitted(
                false, false, -1L, -1L, -1L, -1L));
        assertTrue(transaction.isPending());
        assertTrue(transaction.consumeIfAdmitted(
                true, false, -1L, -1L, -1L, -1L));
        assertFalse(transaction.isPending());
        assertFalse(transaction.consumeIfAdmitted(
                true, false, -1L, -1L, -1L, -1L));
    }

    @Test
    public void exactCorrectionPublicationMustRetireBeforeAdmission() {
        SolveCaptureTransaction transaction = solved(11L);

        assertFalse(transaction.isReady(true, 10L, 11L, 23L, 23L));
        assertFalse(transaction.isReady(true, 11L, 10L, 23L, 23L));
        assertFalse(transaction.isReady(true, 11L, 11L, -1L, -1L));
        assertFalse(transaction.isReady(true, 11L, 11L, 23L, 22L));
        assertTrue(transaction.isReady(true, 11L, 11L, 23L, 23L));
    }

    @Test
    public void staleSolveCannotArmOrCancelAnotherGeneration() {
        SolveCaptureTransaction transaction = new SolveCaptureTransaction();
        transaction.begin(true);
        transaction.bindGeneration(14L);
        transaction.solveSucceeded(13L);
        assertFalse(transaction.isReady(false, -1L, -1L, -1L, -1L));

        transaction.solveFailed(13L);
        assertTrue(transaction.isPending());
        transaction.solveSucceeded(14L);
        assertTrue(transaction.isReady(false, -1L, -1L, -1L, -1L));
        transaction.solveFailed(14L);
        assertFalse(transaction.isPending());
    }

    @Test
    public void ordinarySolveAndExplicitCancellationNeverCapture() {
        SolveCaptureTransaction ordinary = new SolveCaptureTransaction();
        ordinary.begin(false);
        ordinary.bindGeneration(2L);
        ordinary.solveSucceeded(2L);
        assertFalse(ordinary.isPending());

        SolveCaptureTransaction cancelled = solved(3L);
        cancelled.cancel();
        assertFalse(cancelled.consumeIfAdmitted(
                true, false, -1L, -1L, -1L, -1L));
    }

    @Test
    public void disconnectStyleCancellationFencesEveryPreAdmissionPhase() {
        SolveCaptureTransaction beforeGeneration = new SolveCaptureTransaction();
        beforeGeneration.begin(true);
        beforeGeneration.cancel();
        beforeGeneration.bindGeneration(1L);
        beforeGeneration.solveSucceeded(1L);
        assertFalse(beforeGeneration.isPending());

        SolveCaptureTransaction beforeSolve = new SolveCaptureTransaction();
        beforeSolve.begin(true);
        beforeSolve.bindGeneration(2L);
        beforeSolve.cancel();
        beforeSolve.solveSucceeded(2L);
        assertFalse(beforeSolve.isPending());

        SolveCaptureTransaction beforeAdmission = solved(3L);
        beforeAdmission.cancel();
        assertFalse(beforeAdmission.consumeIfAdmitted(
                true, false, -1L, -1L, -1L, -1L));
    }

    private static SolveCaptureTransaction solved(long generation) {
        SolveCaptureTransaction transaction = new SolveCaptureTransaction();
        transaction.begin(true);
        transaction.bindGeneration(generation);
        transaction.solveSucceeded(generation);
        return transaction;
    }
}
