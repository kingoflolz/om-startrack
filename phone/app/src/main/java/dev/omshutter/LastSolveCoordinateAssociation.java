package dev.omshutter;

import dev.omshutter.solver.ObserverCoordinateContext;
import dev.omshutter.solver.SolveResult;

/** Fences an ephemeral observer fix to the exact accepted solve intent and connection. */
final class LastSolveCoordinateAssociation {
    private long nextToken = 1L;
    private long activeToken = -1L;
    private int activeConnectionGeneration = -1;
    private long activeSolveIntentId = -1L;
    private ObserverCoordinateContext activeContext;
    private boolean activeLocationComplete;

    private int displayedConnectionGeneration = -1;
    private long displayedSolveIntentId = -1L;
    private SolveResult displayedResult;
    private ObserverCoordinateContext displayedContext;

    long begin(int connectionGeneration) {
        activeToken = nextToken++;
        activeConnectionGeneration = connectionGeneration;
        activeSolveIntentId = -1L;
        activeContext = null;
        activeLocationComplete = false;
        return activeToken;
    }

    boolean assignIntent(long token, long solveIntentId) {
        if (token != activeToken || solveIntentId < 0L) return false;
        activeSolveIntentId = solveIntentId;
        bindActiveContextIfMatched();
        return true;
    }

    boolean offerContext(
            long token,
            int connectionGeneration,
            ObserverCoordinateContext context) {
        if (token != activeToken
                || connectionGeneration != activeConnectionGeneration
                || context == null) {
            return false;
        }
        activeContext = context;
        activeLocationComplete = true;
        bindActiveContextIfMatched();
        return true;
    }

    boolean markLocationUnavailable(long token, int connectionGeneration) {
        if (token != activeToken || connectionGeneration != activeConnectionGeneration) {
            return false;
        }
        activeContext = null;
        activeLocationComplete = true;
        return true;
    }

    void observe(
            SolveResult result,
            long solvedIntentId,
            int connectionGeneration) {
        if (result == null || solvedIntentId < 0L) {
            displayedConnectionGeneration = -1;
            displayedSolveIntentId = -1L;
            displayedResult = null;
            displayedContext = null;
            return;
        }
        if (result == displayedResult
                && solvedIntentId == displayedSolveIntentId
                && connectionGeneration == displayedConnectionGeneration) {
            bindActiveContextIfMatched();
            return;
        }
        displayedConnectionGeneration = connectionGeneration;
        displayedSolveIntentId = solvedIntentId;
        displayedResult = result;
        displayedContext = null;
        bindActiveContextIfMatched();
    }

    boolean isActiveFor(long solveIntentId, int connectionGeneration) {
        return activeToken >= 0L
                && activeSolveIntentId == solveIntentId
                && activeConnectionGeneration == connectionGeneration;
    }

    boolean isActiveLocationComplete() {
        return activeLocationComplete;
    }

    long getActiveToken() {
        return activeToken;
    }

    int getActiveConnectionGeneration() {
        return activeConnectionGeneration;
    }

    ObserverCoordinateContext getDisplayedContext() {
        return displayedContext;
    }

    SolveResult getDisplayedResult() {
        return displayedResult;
    }

    void clear() {
        activeToken = -1L;
        activeConnectionGeneration = -1;
        activeSolveIntentId = -1L;
        activeContext = null;
        activeLocationComplete = false;
        displayedConnectionGeneration = -1;
        displayedSolveIntentId = -1L;
        displayedResult = null;
        displayedContext = null;
    }

    private void bindActiveContextIfMatched() {
        if (activeContext != null
                && activeSolveIntentId == displayedSolveIntentId
                && activeConnectionGeneration == displayedConnectionGeneration) {
            displayedContext = activeContext;
        }
    }
}
