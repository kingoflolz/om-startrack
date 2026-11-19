package dev.omshutter;

/**
 * Pure state for the shutter bar's slide-to-lock interaction.
 *
 * <p>All timestamps are elapsed-realtime values. A shutter press begins only when the primary
 * pointer starts on {@link Target#SHUTTER}; entering {@link Target#LOCK} commits that press as a
 * lock. While locked, the reverse drag must start on {@code LOCK} and enter {@code SHUTTER} to
 * release it. Merely tapping either target never changes the lock state.
 */
final class ShutterSlideGesture {
    enum Target {
        AF,
        SHUTTER,
        LOCK,
        OUTSIDE
    }

    /** Android-free bounds for hit-testing coordinates expressed in the shutter bar's space. */
    static final class Bounds {
        final float left;
        final float top;
        final float right;
        final float bottom;

        Bounds(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        boolean contains(float x, float y) {
            return x >= left && y >= top && x < right && y < bottom;
        }
    }

    /**
     * Effects the owner applies to the camera and presentation after a pointer transition.
     *
     * <p>{@code lockChanged} describes the logical lock state only. Locking after a drag does not
     * request another shutter-down because the gesture already emitted it on pointer-down.
     */
    static final class Result {
        private final boolean consumed;
        private final boolean pressShutter;
        private final boolean releaseShutter;
        private final boolean lockChanged;
        private final boolean locked;

        private Result(
                boolean consumed,
                boolean pressShutter,
                boolean releaseShutter,
                boolean lockChanged,
                boolean locked) {
            this.consumed = consumed;
            this.pressShutter = pressShutter;
            this.releaseShutter = releaseShutter;
            this.lockChanged = lockChanged;
            this.locked = locked;
        }

        boolean consumed() {
            return consumed;
        }

        boolean pressShutter() {
            return pressShutter;
        }

        boolean releaseShutter() {
            return releaseShutter;
        }

        boolean lockChanged() {
            return lockChanged;
        }

        boolean locked() {
            return locked;
        }
    }

    private enum Drag {
        NONE,
        PRESS_TO_LOCK,
        LOCK_TO_UNLOCK,
        BLOCKED,
        SETTLED
    }

    private Drag drag = Drag.NONE;
    private boolean locked;
    private long lockedAtElapsedMs = -1L;
    private long lastReportedLockedElapsedMs;

    boolean isLocked() {
        return locked;
    }

    boolean isPointerActive() {
        return drag != Drag.NONE;
    }

    boolean isPressingShutter() {
        return drag == Drag.PRESS_TO_LOCK;
    }

    long lockedAtElapsedMs() {
        return locked ? lockedAtElapsedMs : -1L;
    }

    Result onPointerDown(long nowElapsedMs, Target target) {
        if (drag != Drag.NONE) return result(true, false, false, false);

        if (!locked && target == Target.SHUTTER) {
            drag = Drag.PRESS_TO_LOCK;
            return result(true, true, false, false);
        }
        if (locked && target == Target.LOCK) {
            drag = Drag.LOCK_TO_UNLOCK;
            return result(true, false, false, false);
        }
        if (target == Target.SHUTTER || target == Target.LOCK) {
            // Consume taps on the inactive half of the slide control. This prevents a tap on the
            // lock target from locking, and prevents a physical tap on "Unlock" from bypassing
            // the required reverse slide. Accessibility actions use the explicit methods below.
            drag = Drag.BLOCKED;
            return result(true, false, false, false);
        }
        return result(false, false, false, false);
    }

    Result onPointerMove(long nowElapsedMs, Target target) {
        if (drag == Drag.NONE) return result(false, false, false, false);

        if (drag == Drag.PRESS_TO_LOCK && target == Target.LOCK) {
            setLocked(nowElapsedMs);
            drag = Drag.SETTLED;
            return result(true, false, false, true);
        }
        if (drag == Drag.LOCK_TO_UNLOCK && target == Target.SHUTTER) {
            drag = Drag.SETTLED;
            // Keep the logical lock until the service publishes a released snapshot.
            // A release command can fail after it was queued, in which case the same Unlock
            // affordance must remain available for a retry.
            return result(true, false, true, false);
        }
        return result(true, false, false, false);
    }

    Result onPointerUp(long nowElapsedMs, Target target) {
        if (drag == Drag.NONE) return result(false, false, false, false);

        // Android is allowed to deliver DOWN then UP without a MOVE at the destination. Apply
        // that final hit-test before ending the gesture so a quick full-width slide still works.
        if (drag == Drag.PRESS_TO_LOCK && target == Target.LOCK) {
            setLocked(nowElapsedMs);
            drag = Drag.NONE;
            return result(true, false, false, true);
        }
        if (drag == Drag.LOCK_TO_UNLOCK && target == Target.SHUTTER) {
            drag = Drag.NONE;
            return result(true, false, true, false);
        }

        boolean release = drag == Drag.PRESS_TO_LOCK;
        drag = Drag.NONE;
        return result(true, false, release, false);
    }

    /** Cancelling an uncommitted press releases it; a lock already crossed remains committed. */
    Result onPointerCancel(long nowElapsedMs) {
        if (drag == Drag.NONE) return result(false, false, false, false);

        boolean release = drag == Drag.PRESS_TO_LOCK;
        drag = Drag.NONE;
        return result(true, false, release, false);
    }

    /** Accessible equivalent for a long-click on Shutter while unlocked. */
    Result lockForAccessibility(long nowElapsedMs) {
        if (locked || drag != Drag.NONE) return result(false, false, false, false);
        setLocked(nowElapsedMs);
        return result(true, true, false, true);
    }

    /** Accessible equivalent for activating the center Unlock action while locked. */
    Result unlockForAccessibility(long nowElapsedMs) {
        if (!locked || drag != Drag.NONE) return result(false, false, false, false);
        return result(true, false, true, false);
    }

    /** Clears a retained lock only after the camera service reports a released state. */
    Result confirmReleased(long nowElapsedMs) {
        if (!locked) return result(false, false, false, false);
        clearLocked();
        return result(true, false, false, true);
    }

    /**
     * Restores presentation state after Activity or process recreation without commanding the
     * camera. Invalid and future anchors are treated as a lock that began now.
     */
    void restoreLocked(long savedLockedAtElapsedMs, long nowElapsedMs) {
        long now = nonNegative(nowElapsedMs);
        long restoredStart = savedLockedAtElapsedMs < 0L || savedLockedAtElapsedMs > now
                ? now
                : savedLockedAtElapsedMs;
        drag = Drag.NONE;
        locked = true;
        lockedAtElapsedMs = restoredStart;
        lastReportedLockedElapsedMs = now - restoredStart;
    }

    /**
     * Clears all gesture state for a disconnect/reconnect or activity reset.
     *
     * <p>The returned release flag is true whenever this state could own a physical hold.
     */
    Result reset(long nowElapsedMs) {
        boolean wasActive = drag != Drag.NONE;
        boolean wasLocked = locked;
        boolean release = wasLocked || drag == Drag.PRESS_TO_LOCK;
        drag = Drag.NONE;
        clearLocked();
        return new Result(wasActive || wasLocked, false, release, wasLocked, false);
    }

    /** Elapsed lock time, clamped so a regressing input timestamp cannot move the UI backward. */
    long lockedElapsedMs(long nowElapsedMs) {
        if (!locked) return 0L;
        long now = nonNegative(nowElapsedMs);
        long candidate = now <= lockedAtElapsedMs ? 0L : now - lockedAtElapsedMs;
        if (candidate > lastReportedLockedElapsedMs) {
            lastReportedLockedElapsedMs = candidate;
        }
        return lastReportedLockedElapsedMs;
    }

    /** Hit-tests a bar whose three buttons occupy equal, contiguous thirds. */
    static Target targetAt(float x, float y, int barWidth, int barHeight) {
        if (!Float.isFinite(x) || !Float.isFinite(y)
                || barWidth <= 0 || barHeight <= 0
                || x < 0f || y < 0f || x >= barWidth || y >= barHeight) {
            return Target.OUTSIDE;
        }
        float third = barWidth / 3f;
        if (x < third) return Target.AF;
        if (x < third * 2f) return Target.SHUTTER;
        return Target.LOCK;
    }

    /** Hit-tests real child bounds when the bar includes gaps or padding. */
    static Target targetAt(
            float x,
            float y,
            Bounds afBounds,
            Bounds shutterBounds,
            Bounds lockBounds) {
        if (afBounds != null && afBounds.contains(x, y)) return Target.AF;
        if (shutterBounds != null && shutterBounds.contains(x, y)) return Target.SHUTTER;
        if (lockBounds != null && lockBounds.contains(x, y)) return Target.LOCK;
        return Target.OUTSIDE;
    }

    private void setLocked(long nowElapsedMs) {
        locked = true;
        lockedAtElapsedMs = nonNegative(nowElapsedMs);
        lastReportedLockedElapsedMs = 0L;
    }

    private void clearLocked() {
        locked = false;
        lockedAtElapsedMs = -1L;
        lastReportedLockedElapsedMs = 0L;
    }

    private Result result(
            boolean consumed,
            boolean pressShutter,
            boolean releaseShutter,
            boolean lockChanged) {
        return new Result(consumed, pressShutter, releaseShutter, lockChanged, locked);
    }

    private static long nonNegative(long elapsedMs) {
        return Math.max(0L, elapsedMs);
    }
}
