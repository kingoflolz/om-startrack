package dev.omshutter.ble;

/** Pure decisions for releasing a solved correction after STAR capture retirement. */
final class PostTerminalCorrectionPolicy {
    enum AuthorityAction {
        WAIT,
        REFRESH_COHERENT_BANK,
        REQUEST_ORDERED_STATE,
        RELEASE
    }

    enum ReadFailureAction {
        FAIL,
        BEGIN_COHERENT_BANK,
        RETRY_COHERENT_BANK
    }

    private PostTerminalCorrectionPolicy() {}

    /**
     * A notification may trigger a fresh ordered read, but it never releases the correction
     * itself. Only an explicit post-terminal read or coherent fence closes that ordering barrier.
     */
    static AuthorityAction afterState(
            boolean awaitingPostTerminalState,
            boolean notification,
            boolean mutableStateComplete,
            boolean authorityRefreshPending) {
        if (!awaitingPostTerminalState) return AuthorityAction.WAIT;
        if (notification) {
            return mutableStateComplete && !authorityRefreshPending
                    ? AuthorityAction.REQUEST_ORDERED_STATE
                    : AuthorityAction.WAIT;
        }
        if (!mutableStateComplete) return AuthorityAction.REFRESH_COHERENT_BANK;
        return authorityRefreshPending ? AuthorityAction.WAIT : AuthorityAction.RELEASE;
    }

    /** A canonical unfenced unavailable STATE is transient; a completed fence is authoritative. */
    static boolean unavailableIsTerminal(boolean fencedStableUnavailable) {
        return fencedStableUnavailable;
    }

    /**
     * A failed ordered post-terminal STATE is not evidence that authority is unavailable. Start
     * one coherent fence instead; once inside that fence, every nonterminal member failure stays
     * within its existing bounded retry window. Settings verification and invalid handles retain
     * their independent terminal policies.
     */
    static ReadFailureAction afterReadFailure(
            boolean awaitingPostTerminalState,
            boolean stateReadPurpose,
            boolean coherentFenceRead,
            boolean settingsVerificationRead,
            boolean terminalInvalidHandle) {
        if (!awaitingPostTerminalState
                || settingsVerificationRead
                || terminalInvalidHandle) {
            return ReadFailureAction.FAIL;
        }
        if (coherentFenceRead) return ReadFailureAction.RETRY_COHERENT_BANK;
        return stateReadPurpose
                ? ReadFailureAction.BEGIN_COHERENT_BANK
                : ReadFailureAction.FAIL;
    }

    /** A completed no-apply solve has no correction workflow for terminal cleanup to relabel. */
    static boolean discardReplacesStatus(
            boolean deferredCorrectionAvailable, boolean solveProcessing) {
        return deferredCorrectionAvailable || solveProcessing;
    }

    /** Do not erase a completed solve's useful no-apply reason with the retirement stop label. */
    static String retirementStatus(
            String currentStatus,
            boolean solveProcessing,
            boolean sameGenerationSolvedResultAvailable,
            boolean deferredCorrectionAvailable) {
        if (deferredCorrectionAvailable) {
            return "Solved; confirming camera state before applying correction";
        }
        if ((solveProcessing || sameGenerationSolvedResultAvailable)
                && currentStatus != null
                && !currentStatus.trim().isEmpty()) {
            return currentStatus;
        }
        return "STAR complete; correction stopped";
    }
}
