package dev.omshutter.solver;

/** Provenance for the celestial attitude used to calculate an active correction. */
public final class CorrectionSourceSelector {
    public enum Source { SOLVED, MANUAL_GRAVITY }

    private CorrectionSourceSelector() {}
}
