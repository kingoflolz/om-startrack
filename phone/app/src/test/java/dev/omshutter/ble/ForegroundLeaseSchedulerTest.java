package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ForegroundLeaseSchedulerTest {
    private static final long INTERVAL_MS = 15_000L;

    @Test
    public void startsOnlyWhenForegroundAndConnected() {
        FakeEventLoop loop = new FakeEventLoop();
        FakeSender sender = new FakeSender();
        FakeEligibility eligibility = new FakeEligibility();
        ForegroundLeaseScheduler scheduler =
                scheduler(loop, sender, eligibility, new FakeCancellation());

        scheduler.setForeground(true);
        assertFalse(scheduler.isTimerScheduled());
        scheduler.setConnectionReady(true);

        assertTrue(scheduler.isTimerScheduled());
        assertEquals(0L, loop.delayMs);
        assertEquals(1, loop.posts);
    }

    @Test
    public void screenOffBeforeDueCancelsTimerAndQueuesCancellation() {
        FakeEventLoop loop = new FakeEventLoop();
        FakeSender sender = new FakeSender();
        FakeCancellation cancellation = new FakeCancellation();
        ForegroundLeaseScheduler scheduler = scheduler(
                loop, sender, new FakeEligibility(), cancellation);
        scheduler.setForeground(true);
        scheduler.setConnectionReady(true);

        scheduler.setForeground(false);

        assertFalse(scheduler.isTimerScheduled());
        assertEquals(1, loop.removals);
        loop.runRemovedForRace();
        assertEquals(0, sender.attempts);
        assertEquals(1, cancellation.calls);
    }

    @Test
    public void oneWriteMustCompleteBeforeAnotherTimerExists() {
        FakeEventLoop loop = new FakeEventLoop();
        FakeSender sender = new FakeSender();
        ForegroundLeaseScheduler scheduler = scheduler(
                loop, sender, new FakeEligibility(), new FakeCancellation());
        scheduler.setForeground(true);
        scheduler.setConnectionReady(true);

        loop.runScheduled();

        assertEquals(1, sender.attempts);
        assertTrue(scheduler.isWriteInFlight());
        assertFalse(scheduler.isTimerScheduled());
        assertNotNull(sender.completion);

        sender.complete();

        assertFalse(scheduler.isWriteInFlight());
        assertTrue(scheduler.isTimerScheduled());
        assertEquals(INTERVAL_MS, loop.delayMs);
        assertEquals(2, loop.posts);
    }

    @Test
    public void busyTransportSkipsUntilOrdinaryCadence() {
        FakeEventLoop loop = new FakeEventLoop();
        FakeSender sender = new FakeSender();
        sender.admit = false;
        ForegroundLeaseScheduler scheduler = scheduler(
                loop, sender, new FakeEligibility(), new FakeCancellation());
        scheduler.setForeground(true);
        scheduler.setConnectionReady(true);

        loop.runScheduled();

        assertEquals(1, sender.attempts);
        assertFalse(scheduler.isWriteInFlight());
        assertTrue(scheduler.isTimerScheduled());
        assertEquals(INTERVAL_MS, loop.delayMs);
        assertEquals(2, loop.posts);
    }

    @Test
    public void disconnectFencesLateCompletionFromOldConnection() {
        FakeEventLoop loop = new FakeEventLoop();
        FakeSender sender = new FakeSender();
        ForegroundLeaseScheduler scheduler = scheduler(
                loop, sender, new FakeEligibility(), new FakeCancellation());
        scheduler.setForeground(true);
        scheduler.setConnectionReady(true);
        loop.runScheduled();
        Runnable oldCompletion = sender.completion;

        scheduler.setConnectionReady(false);
        scheduler.setConnectionReady(true);
        assertTrue(scheduler.isTimerScheduled());

        oldCompletion.run();

        assertTrue(scheduler.isTimerScheduled());
        assertFalse(scheduler.isWriteInFlight());
        assertEquals(2, loop.posts);
    }

    @Test
    public void foregroundReturnWaitsForSameConnectionWriteCompletion() {
        FakeEventLoop loop = new FakeEventLoop();
        FakeSender sender = new FakeSender();
        ForegroundLeaseScheduler scheduler = scheduler(
                loop, sender, new FakeEligibility(), new FakeCancellation());
        scheduler.setForeground(true);
        scheduler.setConnectionReady(true);
        loop.runScheduled();

        scheduler.setForeground(false);
        scheduler.setForeground(true);
        assertFalse(scheduler.isTimerScheduled());

        sender.complete();

        assertTrue(scheduler.isTimerScheduled());
        assertFalse(scheduler.isWriteInFlight());
    }

    @Test
    public void freshInteractiveQueryFalseOverridesStaleForegroundFlag() {
        FakeEventLoop loop = new FakeEventLoop();
        FakeSender sender = new FakeSender();
        FakeEligibility eligibility = new FakeEligibility();
        FakeCancellation cancellation = new FakeCancellation();
        ForegroundLeaseScheduler scheduler =
                scheduler(loop, sender, eligibility, cancellation);
        scheduler.setForeground(true);
        scheduler.setConnectionReady(true);

        eligibility.eligible = false;
        loop.runScheduled();

        assertEquals(0, sender.attempts);
        assertFalse(scheduler.isForeground());
        assertFalse(scheduler.isTimerScheduled());
        assertEquals(1, cancellation.calls);
    }

    @Test
    public void screenOffWhileWriteInFlightFencesLateCompletion() {
        FakeEventLoop loop = new FakeEventLoop();
        FakeSender sender = new FakeSender();
        FakeCancellation cancellation = new FakeCancellation();
        ForegroundLeaseScheduler scheduler =
                scheduler(loop, sender, new FakeEligibility(), cancellation);
        scheduler.setForeground(true);
        scheduler.setConnectionReady(true);
        loop.runScheduled();
        assertTrue(scheduler.isWriteInFlight());

        scheduler.setForeground(false);
        sender.complete();

        assertFalse(scheduler.isWriteInFlight());
        assertFalse(scheduler.isTimerScheduled());
        assertEquals(1, sender.attempts);
        assertEquals(1, cancellation.calls);
    }

    private static ForegroundLeaseScheduler scheduler(
            FakeEventLoop loop,
            FakeSender sender,
            FakeEligibility eligibility,
            FakeCancellation cancellation) {
        return new ForegroundLeaseScheduler(
                loop, sender, eligibility, cancellation, INTERVAL_MS);
    }

    private static final class FakeEventLoop implements ForegroundLeaseScheduler.EventLoop {
        Runnable scheduled;
        Runnable removed;
        long delayMs;
        int posts;
        int removals;

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            if (scheduled != null) throw new AssertionError("duplicate timer");
            scheduled = runnable;
            this.delayMs = delayMs;
            posts++;
        }

        @Override
        public void removeCallbacks(Runnable runnable) {
            if (scheduled == runnable) {
                removed = scheduled;
                scheduled = null;
            }
            removals++;
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

    private static final class FakeSender implements ForegroundLeaseScheduler.Sender {
        boolean admit = true;
        int attempts;
        Runnable completion;

        @Override
        public boolean trySend(Runnable completion) {
            attempts++;
            if (!admit) return false;
            if (this.completion != null) throw new AssertionError("overlapping write");
            this.completion = completion;
            return true;
        }

        void complete() {
            Runnable next = completion;
            completion = null;
            if (next == null) throw new AssertionError("no write");
            next.run();
        }
    }

    private static final class FakeEligibility
            implements ForegroundLeaseScheduler.Eligibility {
        boolean eligible = true;

        @Override
        public boolean isEligibleNow() {
            return eligible;
        }
    }

    private static final class FakeCancellation implements Runnable {
        int calls;

        @Override
        public void run() {
            calls++;
        }
    }
}
