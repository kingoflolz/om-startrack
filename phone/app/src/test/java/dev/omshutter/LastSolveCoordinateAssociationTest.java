package dev.omshutter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.omshutter.solver.ObserverCoordinateContext;
import dev.omshutter.solver.SolveResult;

import org.junit.Test;

public final class LastSolveCoordinateAssociationTest {
    @Test
    public void matchingFixBindsWhetherItArrivesBeforeOrAfterSolve() {
        LastSolveCoordinateAssociation before = new LastSolveCoordinateAssociation();
        long beforeToken = before.begin(4);
        assertTrue(before.assignIntent(beforeToken, 20L));
        ObserverCoordinateContext firstContext = context(1.0);
        assertTrue(before.offerContext(beforeToken, 4, firstContext));
        SolveResult firstResult = result(10.0);
        before.observe(firstResult, 20L, 4);
        assertSame(firstResult, before.getDisplayedResult());
        assertSame(firstContext, before.getDisplayedContext());

        LastSolveCoordinateAssociation after = new LastSolveCoordinateAssociation();
        long afterToken = after.begin(5);
        assertTrue(after.assignIntent(afterToken, 21L));
        SolveResult secondResult = result(11.0);
        after.observe(secondResult, 21L, 5);
        assertNull(after.getDisplayedContext());
        ObserverCoordinateContext secondContext = context(2.0);
        assertTrue(after.offerContext(afterToken, 5, secondContext));
        assertSame(secondContext, after.getDisplayedContext());
    }

    @Test
    public void staleFixAndRejectedNewIntentCannotReplacePriorDisplay() {
        LastSolveCoordinateAssociation association = new LastSolveCoordinateAssociation();
        long acceptedToken = association.begin(7);
        association.assignIntent(acceptedToken, 30L);
        ObserverCoordinateContext acceptedContext = context(3.0);
        association.offerContext(acceptedToken, 7, acceptedContext);
        SolveResult acceptedResult = result(12.0);
        association.observe(acceptedResult, 30L, 7);

        long rejectedToken = association.begin(7);
        association.assignIntent(rejectedToken, 31L);
        ObserverCoordinateContext rejectedContext = context(4.0);
        association.offerContext(rejectedToken, 7, rejectedContext);
        // The service retains the prior result and its distinct successful intent ID.
        association.observe(acceptedResult, 30L, 7);
        assertSame(acceptedContext, association.getDisplayedContext());
        assertFalse(association.offerContext(acceptedToken, 7, context(5.0)));
        assertFalse(association.offerContext(rejectedToken, 8, context(6.0)));
    }

    @Test
    public void invalidationAndDisconnectClearDisplayedContext() {
        LastSolveCoordinateAssociation association = new LastSolveCoordinateAssociation();
        long token = association.begin(9);
        association.assignIntent(token, 40L);
        association.offerContext(token, 9, context(7.0));
        association.observe(result(13.0), 40L, 9);
        association.observe(null, -1L, 9);
        assertNull(association.getDisplayedResult());
        assertNull(association.getDisplayedContext());

        association.clear();
        assertFalse(association.isActiveFor(40L, 9));
    }

    @Test
    public void refreshingTheSameSolveKeepsOldFixUntilReplacementSucceeds() {
        LastSolveCoordinateAssociation association = new LastSolveCoordinateAssociation();
        SolveResult solve = result(12.0);
        ObserverCoordinateContext oldFix = context(3.0);
        long initial = association.begin(7);
        association.assignIntent(initial, 30L);
        association.offerContext(initial, 7, oldFix);
        association.observe(solve, 30L, 7);

        long failedRefresh = association.begin(7);
        association.assignIntent(failedRefresh, 30L);
        association.observe(solve, 30L, 7);
        assertSame(oldFix, association.getDisplayedContext());
        association.markLocationUnavailable(failedRefresh, 7);
        association.observe(solve, 30L, 7);
        assertSame(oldFix, association.getDisplayedContext());

        long successfulRefresh = association.begin(7);
        association.assignIntent(successfulRefresh, 30L);
        ObserverCoordinateContext newFix = context(4.0);
        assertFalse(association.offerContext(failedRefresh, 7, context(5.0)));
        assertTrue(association.offerContext(successfulRefresh, 7, newFix));
        assertSame(newFix, association.getDisplayedContext());
        assertSame(solve, association.getDisplayedResult());
    }

    private static ObserverCoordinateContext context(double latitude) {
        return ObserverCoordinateContext.of(latitude, -122.0, 1_700_000_000L, 0, 100L);
    }

    private static SolveResult result(double ra) {
        return new SolveResult(
                ra, 20.0, 1.0, 3.0, 1.0,
                new int[]{0, 1, 2, 3}, new int[]{4, 5});
    }
}
