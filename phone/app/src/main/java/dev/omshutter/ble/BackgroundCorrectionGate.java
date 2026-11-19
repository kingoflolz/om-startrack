package dev.omshutter.ble;

/** Idempotent transition gate for background correction suspension and foreground resume. */
final class BackgroundCorrectionGate {
    enum Action {
        NONE,
        SUSPEND,
        RESUME
    }

    private boolean suspended;

    Action suspend() {
        if (suspended) return Action.NONE;
        suspended = true;
        return Action.SUSPEND;
    }

    Action resume() {
        if (!suspended) return Action.NONE;
        suspended = false;
        return Action.RESUME;
    }

    boolean isSuspended() {
        return suspended;
    }

    void clear() {
        suspended = false;
    }
}
