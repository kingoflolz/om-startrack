package dev.omshutter.ble;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Tracks Activity owners and debounces their stop/start handoffs on one serialized event loop.
 *
 * <p>A configuration change stops the old Activity before the replacement binds. Holding the
 * effective visible state for a short grace period prevents that handoff from suspending an
 * in-progress correction or capture. A real background transition still commits when the grace
 * expires. Each scheduled callback is generation-fenced so a canceled callback cannot publish a
 * stale hidden transition. An old Activity stopping cannot hide a newer visible instance.
 */
final class ActivityVisibilityHandoff {
    interface EventLoop {
        void postDelayed(Runnable runnable, long delayMs);
        void removeCallbacks(Runnable runnable);
    }

    interface Sink {
        void apply(boolean visiblyForeground);
    }

    private final EventLoop eventLoop;
    private final Sink sink;
    private final long hiddenGraceMs;

    private final Set<Object> visibleOwners =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean visiblyForeground;
    private Runnable pendingHidden;
    private long generation;

    ActivityVisibilityHandoff(EventLoop eventLoop, Sink sink, long hiddenGraceMs) {
        if (eventLoop == null) throw new NullPointerException("eventLoop");
        if (sink == null) throw new NullPointerException("sink");
        if (hiddenGraceMs <= 0L) throw new IllegalArgumentException("grace must be positive");
        this.eventLoop = eventLoop;
        this.sink = sink;
        this.hiddenGraceMs = hiddenGraceMs;
    }

    void setVisiblyForeground(Object owner, boolean visiblyForeground) {
        if (owner == null) throw new NullPointerException("owner");
        if (visiblyForeground) {
            visibleOwners.add(owner);
            cancelPendingHidden();
            // Every Activity resume reaches the sink even if the logical visible bit never fell.
            // SCREEN_ON is deliberately insufficient; this is the fresh authority that lets the
            // service query interactivity and leave screen-off power policy.
            this.visiblyForeground = true;
            sink.apply(true);
            return;
        }
        if (!visibleOwners.remove(owner) || !visibleOwners.isEmpty()) return;
        cancelPendingHidden();
        if (!this.visiblyForeground) return;

        long scheduledGeneration = generation;
        Runnable scheduled = () -> applyHidden(scheduledGeneration);
        pendingHidden = scheduled;
        eventLoop.postDelayed(scheduled, hiddenGraceMs);
    }

    void cancel() {
        cancelPendingHidden();
    }

    boolean isVisiblyForeground() {
        return visiblyForeground;
    }

    boolean isHiddenPending() {
        return pendingHidden != null;
    }

    private void cancelPendingHidden() {
        generation++;
        Runnable pending = pendingHidden;
        pendingHidden = null;
        if (pending != null) eventLoop.removeCallbacks(pending);
    }

    private void applyHidden(long scheduledGeneration) {
        if (scheduledGeneration != generation || pendingHidden == null) return;
        pendingHidden = null;
        if (!visiblyForeground) return;
        visiblyForeground = false;
        sink.apply(false);
    }
}
