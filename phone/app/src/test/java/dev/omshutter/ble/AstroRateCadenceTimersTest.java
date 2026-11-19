package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.Test;

public final class AstroRateCadenceTimersTest {
    private static final long PUBLICATION_MS = 1_000L;
    private static final long FAST_RETRY_MS = 50L;

    @Test
    public void solvedAndManualSourcesEachGetFiveOpportunitiesInFiveSeconds() {
        for (int source = 0; source < 2; ++source) {
            FakeEventLoop loop = new FakeEventLoop();
            AstroRateCadenceTimers timers =
                    new AstroRateCadenceTimers(loop, PUBLICATION_MS);
            int[] publicationCalls = {0};
            Runnable[] publication = new Runnable[1];
            publication[0] = () -> {
                publicationCalls[0]++;
                timers.rearmPublication(publication[0]);
            };

            timers.rearmPublication(publication[0]);
            loop.runUntil(999L);
            assertEquals(0, publicationCalls[0]);
            loop.runUntil(5_000L);
            assertEquals(5, publicationCalls[0]);
        }
    }

    @Test
    public void failedQueueCanRearmAtNormalPeriodWithoutAddingARateWrite() {
        FakeEventLoop loop = new FakeEventLoop();
        AstroRateCadenceTimers timers =
                new AstroRateCadenceTimers(loop, PUBLICATION_MS);
        int[] opportunities = {0};
        int[] writes = {0};
        Runnable[] publication = new Runnable[1];
        publication[0] = () -> {
            opportunities[0]++;
            boolean queued = AstroRateHeartbeatPolicy.queueAdaptiveOrExact(
                    () -> false, () -> false);
            if (queued) {
                writes[0]++;
            } else {
                // Mirrors the service's false fallback: no write, but cadence continues.
                timers.rearmPublication(publication[0]);
            }
        };

        timers.rearmPublication(publication[0]);
        loop.runUntil(5_000L);
        assertEquals(5, opportunities[0]);
        assertEquals(0, writes[0]);
    }

    @Test
    public void busyRetryUsesTheSameSlotInsteadOfAddingASecondPublication() {
        FakeEventLoop loop = new FakeEventLoop();
        AstroRateCadenceTimers timers =
                new AstroRateCadenceTimers(loop, PUBLICATION_MS);
        int[] opportunityChecks = {0};
        int[] writes = {0};

        timers.rearmPublication(() -> {
            opportunityChecks[0]++;
            timers.retryPublication(() -> {
                opportunityChecks[0]++;
                writes[0]++;
            }, FAST_RETRY_MS);
        });
        loop.runUntil(1_000L);

        loop.runUntil(1_049L);
        assertEquals(1, opportunityChecks[0]);
        assertEquals(0, writes[0]);
        loop.runUntil(1_050L);
        assertEquals(2, opportunityChecks[0]);
        assertEquals(1, writes[0]);
    }

    @Test
    public void ackLatencyConsumesTheCurrentOneSecondPeriod() {
        assertEquals(
                800L,
                AstroRateCadenceTimers.remainingPeriodDelayMs(PUBLICATION_MS, 1_000L, 1_200L));
        assertEquals(
                1L,
                AstroRateCadenceTimers.remainingPeriodDelayMs(PUBLICATION_MS, 1_000L, 2_080L));
        assertEquals(
                PUBLICATION_MS,
                AstroRateCadenceTimers.remainingPeriodDelayMs(PUBLICATION_MS, -1L, 2_080L));
    }

    @Test
    public void removedCallbacksCannotRunAfterReplacementOrReset() {
        FakeEventLoop loop = new FakeEventLoop();
        AstroRateCadenceTimers timers =
                new AstroRateCadenceTimers(loop, PUBLICATION_MS);
        int[] oldPublicationCalls = {0};
        int[] currentPublicationCalls = {0};
        int[] cancelledPublicationCalls = {0};

        timers.rearmPublication(() -> oldPublicationCalls[0]++);
        timers.rearmPublication(() -> currentPublicationCalls[0]++);
        loop.runLastRemovedForRace();
        assertEquals(0, oldPublicationCalls[0]);
        loop.runUntil(PUBLICATION_MS);
        assertEquals(1, currentPublicationCalls[0]);

        timers.rearmPublication(() -> cancelledPublicationCalls[0]++);
        timers.cancelPublication();
        loop.runLastRemovedForRace();
        assertEquals(0, cancelledPublicationCalls[0]);
    }

    private static final class FakeEventLoop implements AstroRateCadenceTimers.EventLoop {
        private static final class Scheduled {
            private final Runnable runnable;
            private final long dueMs;

            private Scheduled(Runnable runnable, long dueMs) {
                this.runnable = runnable;
                this.dueMs = dueMs;
            }
        }

        private final List<Scheduled> scheduled = new ArrayList<>();
        private final List<Runnable> removedForRace = new ArrayList<>();
        private long nowMs;

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            scheduled.add(new Scheduled(runnable, nowMs + delayMs));
        }

        @Override
        public void removeCallbacks(Runnable runnable) {
            int before = scheduled.size();
            scheduled.removeIf(item -> item.runnable == runnable);
            if (before != scheduled.size()) {
                removedForRace.add(runnable);
            }
        }

        private void runLastRemovedForRace() {
            removedForRace.remove(removedForRace.size() - 1).run();
        }

        private void runUntil(long deadlineMs) {
            while (true) {
                Scheduled next = scheduled.stream()
                        .filter(item -> item.dueMs <= deadlineMs)
                        .min(Comparator.comparingLong(item -> item.dueMs))
                        .orElse(null);
                if (next == null) break;
                scheduled.remove(next);
                nowMs = next.dueMs;
                next.runnable.run();
            }
            nowMs = deadlineMs;
        }
    }
}
