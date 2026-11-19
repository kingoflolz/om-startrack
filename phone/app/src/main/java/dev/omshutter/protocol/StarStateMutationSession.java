package dev.omshutter.protocol;

/**
 * One-shot semantic barrier for the sole STAR STATE mutation.
 *
 * <p>ATT success proves reservation only. Completion requires a subsequently observed,
 * authoritative STATE matching live indices, recipe indices, detector configuration, and the
 * original catalog bank. The session never produces a replay value after transport ambiguity.</p>
 */
public final class StarStateMutationSession {
    public enum Phase {
        NEW,
        WAITING_FOR_ATT,
        WAITING_FOR_STATE,
        READY,
        FAILED
    }

    public static final class Snapshot {
        private final Phase phase;
        private final StarProtocol.MutationTarget target;
        private final StarProtocol.State state;
        private final String detail;

        private Snapshot(
                Phase phase,
                StarProtocol.MutationTarget target,
                StarProtocol.State state,
                String detail) {
            this.phase = phase;
            this.target = target;
            this.state = state;
            this.detail = detail;
        }

        public Phase getPhase() { return phase; }
        public StarProtocol.MutationTarget getTarget() { return target; }
        public StarProtocol.State getState() { return state; }
        public String getDetail() { return detail; }
        public boolean isWriteInFlight() { return phase == Phase.WAITING_FOR_ATT; }
        public boolean isWriteComplete() {
            return phase == Phase.WAITING_FOR_STATE || phase == Phase.READY;
        }
        public boolean isReady() { return phase == Phase.READY; }
        public boolean isTerminalFailed() { return phase == Phase.FAILED; }
    }

    private Phase phase = Phase.NEW;
    private StarProtocol.MutationTarget target;
    private StarProtocol.State state;
    private String detail = "Ready to apply STAR state";

    public synchronized byte[] begin(StarProtocol.MutationTarget requested) {
        if (phase != Phase.NEW) throw new IllegalStateException("STATE mutation already began");
        if (requested == null) throw new IllegalArgumentException("STATE target is null");
        target = requested;
        phase = Phase.WAITING_FOR_ATT;
        detail = "Waiting for STAR STATE write completion";
        return StarProtocol.encodeStateWrite(requested);
    }

    public synchronized void onWriteComplete(boolean success) {
        if (phase != Phase.WAITING_FOR_ATT) return;
        if (!success) {
            fail("Camera rejected the STAR STATE write");
            return;
        }
        // A matching STATE observed before this callback is only a baseline. Discard it and
        // require a later read/notification to establish the semantic barrier.
        state = null;
        phase = Phase.WAITING_FOR_STATE;
        detail = "STATE write accepted; waiting for matching authoritative state";
    }

    public synchronized void onState(StarProtocol.State authoritative) {
        if (phase != Phase.WAITING_FOR_STATE || authoritative == null) return;
        state = authoritative;
        if (target.matches(authoritative)) {
            phase = Phase.READY;
            detail = "Camera confirmed the complete STAR state";
        }
    }

    public synchronized void transportFailed(String reason) {
        if (phase == Phase.READY || phase == Phase.FAILED) return;
        fail(reason == null || reason.isEmpty()
                ? "STAR STATE transport ended before authoritative confirmation"
                : reason);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(phase, target, state, detail);
    }

    private void fail(String reason) {
        phase = Phase.FAILED;
        detail = reason;
    }
}
