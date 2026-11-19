package dev.omshutter.ble;

/** Handler-owned admission sequence. A failed or cancelled step can never issue full press. */
final class IbisPrepositionCapture {
    enum State { IDLE, HALF_PRESS, APPLY, SETTLE, FULL_PRESS }
    private State state = State.IDLE;

    boolean begin() {
        if (isPreparing()) return false;
        state = State.HALF_PRESS;
        return true;
    }

    boolean halfPressCompleted(boolean success) {
        if (state != State.HALF_PRESS) return false;
        state = success ? State.APPLY : State.IDLE;
        return success;
    }

    boolean applyCompleted(boolean success) {
        if (state != State.APPLY) return false;
        state = success ? State.SETTLE : State.IDLE;
        return success;
    }

    boolean settleCompleted() {
        if (state != State.SETTLE) return false;
        state = State.FULL_PRESS;
        return true;
    }

    boolean fullPressCompleted(boolean success) {
        if (state != State.FULL_PRESS) return false;
        state = State.IDLE;
        return success;
    }

    boolean isPreparing() { return state != State.IDLE; }
    State getState() { return state; }
    void cancel() { state = State.IDLE; }

    /** Only finite fixed camera shutter labels are useful for a pre-position trajectory. */
    static double exposureSeconds(String label) {
        if (label == null) return Double.NaN;
        String text = label.trim();
        try {
            double seconds;
            if (text.matches("1/[0-9]+(?:\\.[0-9]+)?")) {
                seconds = 1.0 / Double.parseDouble(text.substring(2));
            } else {
                boolean explicitSeconds = text.endsWith("\"") || text.endsWith("s");
                if (explicitSeconds) {
                    text = text.substring(0, text.length() - 1).trim();
                }
                if (!text.matches("[0-9]+(?:\\.[0-9]+)?")) return Double.NaN;
                // As in ExposureOptionDisplay, a bare camera label is a reciprocal denominator.
                double number = Double.parseDouble(text);
                seconds = explicitSeconds ? number : 1.0 / number;
            }
            return Double.isFinite(seconds) && seconds > 0.0 && seconds <= 3600.0
                    ? seconds : Double.NaN;
        } catch (NumberFormatException error) {
            return Double.NaN;
        }
    }
}
