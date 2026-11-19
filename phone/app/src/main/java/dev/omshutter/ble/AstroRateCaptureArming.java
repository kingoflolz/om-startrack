package dev.omshutter.ble;

/** ACK gate that prevents STAR CONTROL from passing an active lease's arming RATE write. */
final class AstroRateCaptureArming {
    enum Decision {
        START_WITHOUT_RATE,
        WAIT_FOR_ACTIVE_ACK,
        START_WITH_RATE,
        FAIL_CAPTURE,
        NONE
    }

    private boolean waiting;

    Decision begin(boolean activeRate) {
        if (waiting) throw new IllegalStateException("capture RATE arming is already active");
        if (!activeRate) return Decision.START_WITHOUT_RATE;
        waiting = true;
        return Decision.WAIT_FOR_ACTIVE_ACK;
    }

    Decision activeWriteAcknowledged() {
        if (!waiting) return Decision.NONE;
        waiting = false;
        return Decision.START_WITH_RATE;
    }

    Decision zeroAcknowledged() {
        if (!waiting) return Decision.NONE;
        waiting = false;
        return Decision.START_WITHOUT_RATE;
    }

    Decision zeroProvenUnapplied() {
        if (!waiting) return Decision.NONE;
        waiting = false;
        return Decision.FAIL_CAPTURE;
    }

    boolean isWaiting() { return waiting; }

    void reset() { waiting = false; }
}
