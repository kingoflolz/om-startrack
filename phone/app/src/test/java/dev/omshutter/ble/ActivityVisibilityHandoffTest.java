package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ActivityVisibilityHandoffTest {
    private static final long GRACE_MS = 1_000L;
    private static final Object OWNER = new Object();

    @Test
    public void configurationStopStartDoesNotPublishHidden() {
        FakeEventLoop loop = new FakeEventLoop();
        List<Boolean> applied = new ArrayList<>();
        ActivityVisibilityHandoff handoff = handoff(loop, applied);

        handoff.setVisiblyForeground(OWNER, true);
        handoff.setVisiblyForeground(OWNER, false);
        assertTrue(handoff.isHiddenPending());
        handoff.setVisiblyForeground(new Object(), true);
        loop.runRemovedForRace();

        // The second true is an intentional reconciliation signal: the Activity stayed logically
        // visible, but the service must re-query screen interactivity after the handoff.
        assertEquals(Arrays.asList(true, true), applied);
        assertTrue(handoff.isVisiblyForeground());
        assertFalse(handoff.isHiddenPending());
    }

    @Test
    public void ordinaryBackgroundCommitsAfterGrace() {
        FakeEventLoop loop = new FakeEventLoop();
        List<Boolean> applied = new ArrayList<>();
        ActivityVisibilityHandoff handoff = handoff(loop, applied);

        handoff.setVisiblyForeground(OWNER, true);
        handoff.setVisiblyForeground(OWNER, false);

        assertEquals(GRACE_MS, loop.delayMs);
        assertEquals(Arrays.asList(true), applied);
        loop.runScheduled();
        assertEquals(Arrays.asList(true, false), applied);
        assertFalse(handoff.isVisiblyForeground());
    }

    @Test
    public void rotateThenImmediateBackgroundStillCommitsLastStop() {
        FakeEventLoop loop = new FakeEventLoop();
        List<Boolean> applied = new ArrayList<>();
        ActivityVisibilityHandoff handoff = handoff(loop, applied);

        handoff.setVisiblyForeground(OWNER, true);
        handoff.setVisiblyForeground(OWNER, false);
        handoff.setVisiblyForeground(OWNER, true);
        handoff.setVisiblyForeground(OWNER, false);
        loop.runScheduled();

        assertEquals(Arrays.asList(true, true, false), applied);
        assertFalse(handoff.isVisiblyForeground());
        assertFalse(handoff.isHiddenPending());
    }

    @Test
    public void supersededTimerCannotHideNewForeground() {
        FakeEventLoop loop = new FakeEventLoop();
        List<Boolean> applied = new ArrayList<>();
        ActivityVisibilityHandoff handoff = handoff(loop, applied);

        handoff.setVisiblyForeground(OWNER, true);
        handoff.setVisiblyForeground(OWNER, false);
        handoff.setVisiblyForeground(OWNER, true);
        loop.runRemovedForRace();

        assertEquals(Arrays.asList(true, true), applied);
        assertTrue(handoff.isVisiblyForeground());
    }

    @Test
    public void oldActivityStoppingAfterNewResumeCannotHideTheNewActivity() {
        FakeEventLoop loop = new FakeEventLoop();
        List<Boolean> applied = new ArrayList<>();
        ActivityVisibilityHandoff handoff = handoff(loop, applied);
        Object newer = new Object();

        handoff.setVisiblyForeground(OWNER, true);
        handoff.setVisiblyForeground(newer, true);
        handoff.setVisiblyForeground(OWNER, false);
        assertFalse(handoff.isHiddenPending());
        assertTrue(handoff.isVisiblyForeground());
        assertEquals(Arrays.asList(true, true), applied);

        handoff.setVisiblyForeground(newer, false);
        assertTrue(handoff.isHiddenPending());
        loop.runScheduled();
        assertFalse(handoff.isVisiblyForeground());
        assertEquals(Arrays.asList(true, true, false), applied);
    }

    @Test
    public void bindAndResumeForOneOwnerNeedOnlyOneStopAndStillReplayScreenAuthority() {
        FakeEventLoop loop = new FakeEventLoop();
        List<Boolean> applied = new ArrayList<>();
        ActivityVisibilityHandoff handoff = handoff(loop, applied);
        handoff.setVisiblyForeground(OWNER, true);
        handoff.setVisiblyForeground(OWNER, true);
        assertEquals(Arrays.asList(true, true), applied);
        handoff.setVisiblyForeground(OWNER, false);
        loop.runScheduled();
        assertEquals(Arrays.asList(true, true, false), applied);
    }

    @Test
    public void repeatedOldStopAfterReplacementCannotScheduleAnotherHiddenTransition() {
        FakeEventLoop loop = new FakeEventLoop();
        List<Boolean> applied = new ArrayList<>();
        ActivityVisibilityHandoff handoff = handoff(loop, applied);
        handoff.setVisiblyForeground(OWNER, true);
        handoff.setVisiblyForeground(OWNER, false);
        handoff.setVisiblyForeground(new Object(), true);
        handoff.setVisiblyForeground(OWNER, false);
        loop.runRemovedForRace();
        assertFalse(handoff.isHiddenPending());
        assertTrue(handoff.isVisiblyForeground());
        assertEquals(Arrays.asList(true, true), applied);
    }

    private static ActivityVisibilityHandoff handoff(
            FakeEventLoop loop, List<Boolean> applied) {
        return new ActivityVisibilityHandoff(loop, applied::add, GRACE_MS);
    }

    private static final class FakeEventLoop implements ActivityVisibilityHandoff.EventLoop {
        Runnable scheduled;
        Runnable removed;
        long delayMs;

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            if (scheduled != null) throw new AssertionError("duplicate timer");
            scheduled = runnable;
            this.delayMs = delayMs;
        }

        @Override
        public void removeCallbacks(Runnable runnable) {
            if (scheduled == runnable) {
                removed = scheduled;
                scheduled = null;
            }
        }

        void runScheduled() {
            Runnable next = scheduled;
            if (next == null) throw new AssertionError("no timer");
            scheduled = null;
            next.run();
        }

        void runRemovedForRace() {
            Runnable next = removed;
            removed = null;
            if (next != null) next.run();
        }
    }
}
