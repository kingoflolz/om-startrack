package dev.omshutter.ble;

/**
 * Service-owned one-shot continuation from one exact plate solve to one stock-camera photo.
 *
 * <p>The transaction deliberately stays pending until the BLE owner has actually admitted the
 * stock shutter operation. A solved result alone is not enough: the STAR CONTROL write callback,
 * restored STATE read, correction publication, and every other serialized GATT owner may still
 * be retiring.</p>
 */
final class SolveCaptureTransaction {
    private enum Phase {
        IDLE,
        WAITING_FOR_GENERATION,
        WAITING_FOR_SOLUTION,
        WAITING_FOR_ADMISSION
    }

    private Phase phase = Phase.IDLE;
    private long solveGeneration = -1L;

    void begin(boolean captureAfterSolve) {
        phase = captureAfterSolve ? Phase.WAITING_FOR_GENERATION : Phase.IDLE;
        solveGeneration = -1L;
    }

    void bindGeneration(long generation) {
        if (phase != Phase.WAITING_FOR_GENERATION || generation < 0L) return;
        solveGeneration = generation;
        phase = Phase.WAITING_FOR_SOLUTION;
    }

    void solveSucceeded(long generation) {
        if (phase == Phase.WAITING_FOR_SOLUTION && generation == solveGeneration) {
            phase = Phase.WAITING_FOR_ADMISSION;
        }
    }

    void solveFailed(long generation) {
        if ((phase == Phase.WAITING_FOR_SOLUTION
                || phase == Phase.WAITING_FOR_ADMISSION)
                && generation == solveGeneration) {
            cancel();
        }
    }

    /**
     * Consumes only after the same-thread caller has admitted the complete stock shutter click.
     * A temporary control-path race therefore leaves the transaction retryable.
     */
    boolean consumeIfAdmitted(
            boolean admitted,
            boolean correctionRequired,
            long desiredSolveGeneration,
            long acknowledgedSolveGeneration,
            long desiredCorrectionGeneration,
            long acknowledgedCorrectionGeneration) {
        if (!admitted || !isReady(
                correctionRequired,
                desiredSolveGeneration,
                acknowledgedSolveGeneration,
                desiredCorrectionGeneration,
                acknowledgedCorrectionGeneration)) {
            return false;
        }
        cancel();
        return true;
    }

    boolean isReady(
            boolean correctionRequired,
            long desiredSolveGeneration,
            long acknowledgedSolveGeneration,
            long desiredCorrectionGeneration,
            long acknowledgedCorrectionGeneration) {
        if (phase != Phase.WAITING_FOR_ADMISSION || solveGeneration < 0L) return false;
        return !correctionRequired
                || (desiredSolveGeneration == solveGeneration
                    && acknowledgedSolveGeneration == solveGeneration
                    && desiredCorrectionGeneration >= 0L
                    && desiredCorrectionGeneration == acknowledgedCorrectionGeneration);
    }

    void cancel() {
        phase = Phase.IDLE;
        solveGeneration = -1L;
    }

    boolean isPending() {
        return phase != Phase.IDLE;
    }

    long getSolveGeneration() {
        return solveGeneration;
    }
}
