package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PostTerminalCorrectionPolicyTest {
    @Test
    public void incompleteOrderedStateStartsCoherentRefresh() {
        assertEquals(
                PostTerminalCorrectionPolicy.AuthorityAction.REFRESH_COHERENT_BANK,
                PostTerminalCorrectionPolicy.afterState(true, false, false, false));
    }

    @Test
    public void completeNotificationRequestsOrderedReadButNeverReleasesDirectly() {
        assertEquals(
                PostTerminalCorrectionPolicy.AuthorityAction.REQUEST_ORDERED_STATE,
                PostTerminalCorrectionPolicy.afterState(true, true, true, false));
        assertEquals(
                PostTerminalCorrectionPolicy.AuthorityAction.WAIT,
                PostTerminalCorrectionPolicy.afterState(true, true, true, true));
    }

    @Test
    public void completeOrderedStateReleasesExactlyAfterRefreshEnds() {
        assertEquals(
                PostTerminalCorrectionPolicy.AuthorityAction.WAIT,
                PostTerminalCorrectionPolicy.afterState(true, false, true, true));
        assertEquals(
                PostTerminalCorrectionPolicy.AuthorityAction.RELEASE,
                PostTerminalCorrectionPolicy.afterState(true, false, true, false));
    }

    @Test
    public void unavailableStateIsTerminalOnlyAfterCoherentFence() {
        assertFalse(PostTerminalCorrectionPolicy.unavailableIsTerminal(false));
        assertTrue(PostTerminalCorrectionPolicy.unavailableIsTerminal(true));
    }

    @Test
    public void orderedReadFailureBeginsFenceAndFenceMembersUseBoundedRetry() {
        assertEquals(
                PostTerminalCorrectionPolicy.ReadFailureAction.BEGIN_COHERENT_BANK,
                PostTerminalCorrectionPolicy.afterReadFailure(
                        true, true, false, false, false));
        assertEquals(
                PostTerminalCorrectionPolicy.ReadFailureAction.RETRY_COHERENT_BANK,
                PostTerminalCorrectionPolicy.afterReadFailure(
                        true, false, true, false, false));
    }

    @Test
    public void orderedReadFailureDoesNotOverrideTerminalOrIndependentPolicies() {
        assertEquals(
                PostTerminalCorrectionPolicy.ReadFailureAction.FAIL,
                PostTerminalCorrectionPolicy.afterReadFailure(
                        true, true, false, false, true));
        assertEquals(
                PostTerminalCorrectionPolicy.ReadFailureAction.FAIL,
                PostTerminalCorrectionPolicy.afterReadFailure(
                        true, true, false, true, false));
        assertEquals(
                PostTerminalCorrectionPolicy.ReadFailureAction.FAIL,
                PostTerminalCorrectionPolicy.afterReadFailure(
                        false, true, false, false, false));
    }

    @Test
    public void discardOnlyRelabelsAnActiveCorrectionWorkflow() {
        assertFalse(PostTerminalCorrectionPolicy.discardReplacesStatus(false, false));
        assertTrue(PostTerminalCorrectionPolicy.discardReplacesStatus(true, false));
        assertTrue(PostTerminalCorrectionPolicy.discardReplacesStatus(false, true));
    }

    @Test
    public void terminalRetirementPreservesCompletedNoApplyReason() {
        assertEquals(
                "Solved; Camera tilt unavailable",
                PostTerminalCorrectionPolicy.retirementStatus(
                        "Solved; Camera tilt unavailable", false, true, false));
        assertEquals(
                "Plate solved; correction is disabled",
                PostTerminalCorrectionPolicy.retirementStatus(
                        "Plate solved; correction is disabled", false, true, false));
    }

    @Test
    public void terminalRetirementShowsApplyingWhileCorrectionIsDeferred() {
        assertEquals(
                "Solved; confirming camera state before applying correction",
                PostTerminalCorrectionPolicy.retirementStatus(
                        "Correction update queued", true, true, true));
        assertEquals(
                "Capturing stars",
                PostTerminalCorrectionPolicy.retirementStatus(
                        "Capturing stars", true, false, false));
        assertEquals(
                "STAR complete; correction stopped",
                PostTerminalCorrectionPolicy.retirementStatus(
                        "Correction stopped", false, false, false));
    }
}
