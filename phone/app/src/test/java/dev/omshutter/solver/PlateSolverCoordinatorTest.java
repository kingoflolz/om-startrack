package dev.omshutter.solver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public class PlateSolverCoordinatorTest {
    @Test
    public void listenerReplayCannotOvertakeANewerPublication() throws Exception {
        FirstDispatchGate executor = new FirstDispatchGate();
        PlateSolverCoordinator coordinator = new PlateSolverCoordinator(
                () -> { throw new AssertionError("missing-focal publication created an engine"); },
                executor);
        List<SolveSnapshot.State> delivered = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread registration = new Thread(() -> {
            try {
                coordinator.addListener(snapshot -> delivered.add(snapshot.getState()));
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        }, "listener-registration");
        registration.start();
        assertTrue(executor.firstDispatchEntered.await(2, TimeUnit.SECONDS));

        Thread publication = new Thread(() -> {
            try {
                assertTrue(coordinator.submitMissingFocal(1, true));
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        }, "listener-publication");
        publication.start();
        try {
            executor.awaitPublicationAttempt(publication);
        } finally {
            executor.releaseFirstDispatch.countDown();
        }

        registration.join(2_000L);
        publication.join(2_000L);
        assertFalse(registration.isAlive());
        assertFalse(publication.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());

        executor.drain();
        assertEquals(SolveSnapshot.State.MISSING_METADATA,
                delivered.get(delivered.size() - 1));
        coordinator.close();
    }

    @Test
    public void repeatedPublicationIsSolvedExactlyOnceOffCallerThread() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger solves = new AtomicInteger();
        AtomicReference<Thread> solveThread = new AtomicReference<>();
        CountDownLatch solved = new CountDownLatch(1);
        SolverEngine engine = new SolverEngine() {
            @Override
            public Outcome solve(SolveRequest request) {
                solves.incrementAndGet();
                solveThread.set(Thread.currentThread());
                return Outcome.solved(solution(), "Solved", false);
            }

            @Override public void close() {}
        };
        PlateSolverCoordinator coordinator = new PlateSolverCoordinator(
                () -> { creates.incrementAndGet(); return engine; }, Runnable::run);
        coordinator.addListener(snapshot -> {
            if (snapshot.getState() == SolveSnapshot.State.SOLVED) solved.countDown();
        });

        Thread caller = Thread.currentThread();
        SolveRequest request = request(9, true);
        assertTrue(coordinator.submit(request));
        assertFalse(coordinator.submit(request));
        assertTrue(solved.await(2, TimeUnit.SECONDS));

        assertEquals(1, creates.get());
        assertEquals(1, solves.get());
        assertNotSame(caller, solveThread.get());
        assertEquals(SolveSnapshot.State.SOLVED, coordinator.getSnapshot().getState());
        assertFalse(coordinator.getSnapshot().isDistortionFallback());
        coordinator.close();
    }

    @Test
    public void solvedSnapshotCarriesExactWorkerDurationIncludingColdInitialization()
            throws Exception {
        CountDownLatch solved = new CountDownLatch(1);
        AtomicInteger ticks = new AtomicInteger();
        long started = 10_000_000_000L;
        long duration = 3_250_000_000L;
        LongSupplier clock = () -> ticks.getAndIncrement() == 0
                ? started : started + duration;
        PlateSolverCoordinator coordinator = new PlateSolverCoordinator(
                () -> new SolverEngine() {
                    @Override
                    public Outcome solve(SolveRequest request) {
                        return Outcome.solved(solution(), "solved", true);
                    }

                    @Override public void close() {}
                },
                Runnable::run,
                clock);
        coordinator.addListener(snapshot -> {
            if (snapshot.getState() == SolveSnapshot.State.SOLVED) solved.countDown();
        });

        assertTrue(coordinator.submit(request(90, false)));
        assertTrue(solved.await(2, TimeUnit.SECONDS));
        SolveSnapshot snapshot = coordinator.getSnapshot();
        assertTrue(snapshot.hasSolveDuration());
        assertEquals(duration, snapshot.getSolveDurationNanos());
        coordinator.close();
    }

    @Test
    public void nonWorkerSnapshotsDoNotInventSolveDuration() {
        assertFalse(SolveSnapshot.idle().hasSolveDuration());
        assertEquals(
                SolveSnapshot.DURATION_UNAVAILABLE_NANOS,
                SolveSnapshot.idle().getSolveDurationNanos());
        assertEquals(0L, PlateSolverCoordinator.elapsedNanos(5L, 4L));
    }

    @Test
    public void missingFocalPublishesUnavailableWithoutCreatingEngine() {
        AtomicInteger creates = new AtomicInteger();
        PlateSolverCoordinator coordinator = new PlateSolverCoordinator(
                () -> { creates.incrementAndGet(); throw new AssertionError(); }, Runnable::run);

        assertTrue(coordinator.submitMissingFocal(11, true));
        assertFalse(coordinator.submitMissingFocal(11, true));

        SolveSnapshot snapshot = coordinator.getSnapshot();
        assertEquals(SolveSnapshot.State.MISSING_METADATA, snapshot.getState());
        assertTrue(snapshot.isLensMetadataMissing());
        assertTrue(snapshot.isDistortionFallback());
        assertEquals(0, creates.get());
        coordinator.close();
    }

    @Test
    public void submitNeverWaitsForBlockingSolveAndCloseSuppressesLateResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        SolverEngine engine = new SolverEngine() {
            @Override
            public Outcome solve(SolveRequest request) throws Exception {
                entered.countDown();
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException ignored) {
                        // Model a native solve which is not Java-interruptible.
                    }
                }
                finished.countDown();
                return Outcome.solved(solution(), "late", true);
            }

            @Override public void close() {
                closes.incrementAndGet();
                release.countDown();
            }
        };
        PlateSolverCoordinator coordinator = new PlateSolverCoordinator(() -> engine, Runnable::run);

        long before = System.nanoTime();
        assertTrue(coordinator.submit(request(12, false)));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        assertTrue("submit blocked for " + elapsedMs + "ms", elapsedMs < 250);
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        long closeBefore = System.nanoTime();
        coordinator.close();
        long closeElapsedMs = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - closeBefore);
        assertTrue("close blocked for " + closeElapsedMs + "ms", closeElapsedMs < 250);
        assertEquals(SolveSnapshot.State.CLOSED, coordinator.getSnapshot().getState());
        assertEquals(1, closes.get());
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertEquals(SolveSnapshot.State.CLOSED, coordinator.getSnapshot().getState());
    }

    @Test
    public void invalidationSuppressesOldSolveAndAllowsNextGeneration() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch firstFinished = new CountDownLatch(1);
        CountDownLatch secondSolved = new CountDownLatch(1);
        AtomicInteger solves = new AtomicInteger();
        SolverEngine engine = new SolverEngine() {
            @Override
            public Outcome solve(SolveRequest request) throws Exception {
                int call = solves.incrementAndGet();
                if (call == 1) {
                    firstEntered.countDown();
                    releaseFirst.await();
                    firstFinished.countDown();
                }
                return Outcome.solved(solution(), "solved", true);
            }

            @Override public void close() { releaseFirst.countDown(); }
        };
        PlateSolverCoordinator coordinator = new PlateSolverCoordinator(() -> engine, Runnable::run);
        coordinator.addListener(snapshot -> {
            if (snapshot.getGeneration() == 13
                    && snapshot.getState() == SolveSnapshot.State.SOLVED) {
                secondSolved.countDown();
            }
        });

        assertTrue(coordinator.submit(request(12, false)));
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
        coordinator.invalidate("camera changed");
        assertEquals(SolveSnapshot.State.IDLE, coordinator.getSnapshot().getState());
        assertEquals("camera changed", coordinator.getSnapshot().getDetail());
        releaseFirst.countDown();
        assertTrue(firstFinished.await(2, TimeUnit.SECONDS));
        assertEquals(SolveSnapshot.State.IDLE, coordinator.getSnapshot().getState());

        assertTrue(coordinator.submit(request(13, false)));
        assertTrue(secondSolved.await(2, TimeUnit.SECONDS));
        assertEquals(2, solves.get());
        coordinator.close();
    }

    @Test
    public void rejectedNewCaptureClearsSolutionBeforePublishingFailure()
            throws Exception {
        CountDownLatch solved = new CountDownLatch(1);
        List<String> publications = new ArrayList<>();
        SolverEngine engine = new SolverEngine() {
            @Override
            public Outcome solve(SolveRequest request) {
                return Outcome.solved(solution(), "solved", true);
            }

            @Override public void close() {}
        };
        PlateSolverCoordinator coordinator = new PlateSolverCoordinator(() -> engine, Runnable::run);
        coordinator.addListener(snapshot -> {
            publications.add(snapshot.getState().name());
            if (snapshot.getState() == SolveSnapshot.State.SOLVED) {
                solved.countDown();
            }
        });
        assertTrue(coordinator.submit(request(20, false)));
        assertTrue(solved.await(2, TimeUnit.SECONDS));
        assertEquals(SolveSnapshot.State.SOLVED, coordinator.getSnapshot().getState());
        publications.clear();

        // CameraConnectionService performs this before replacing/publishing the STAR session,
        // even when capture preflight immediately rejects the new request.
        coordinator.invalidate("Plate solution cleared because a new STAR detection started");
        publications.add("STAR_FAILED");

        SolveSnapshot cleared = coordinator.getSnapshot();
        assertEquals(SolveSnapshot.State.IDLE, cleared.getState());
        assertEquals(20, cleared.getGeneration());
        assertTrue(cleared.getResult() == null);
        assertEquals(
                "Plate solution cleared because a new STAR detection started",
                cleared.getDetail());
        assertEquals(2, publications.size());
        assertEquals(SolveSnapshot.State.IDLE.name(), publications.get(0));
        assertEquals("STAR_FAILED", publications.get(1));
        // No next solve request is submitted: preflight failure is terminal for this capture.
        coordinator.close();
    }

    @Test
    public void requestsAndResultsDefensivelyCopyBoundedInputs() {
        ArrayList<SolveRequest.Source> sources = new ArrayList<>();
        sources.add(new SolveRequest.Source(1.0, 2.0, 3.0f));
        SolveRequest request = new SolveRequest(1, 300.0, "lens", sources);
        sources.clear();
        assertEquals(1, request.getSources().size());
        try {
            request.getSources().clear();
            throw new AssertionError("request sources were mutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }

        int[] observed = {1, 2};
        SolveResult result = new SolveResult(
                12.0, -20.0, 181.0, 3.3, 1.2,
                new int[]{0, 3, 4, 5}, observed);
        observed[0] = 99;
        assertEquals(1, result.getMatchedSourceIndices()[0]);
        assertEquals(-179.0, result.getRollDeg(), 0.0);

        List<SolveRequest.Source> tooMany = new ArrayList<>();
        for (int index = 0; index <= SolveRequest.MAX_SOURCES; index++) {
            tooMany.add(new SolveRequest.Source(0.0, 0.0, 1.0f));
        }
        try {
            new SolveRequest(2, 300.0, "", tooMany);
            throw new AssertionError("oversized request was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("too many"));
        }
    }

    private static SolveRequest request(long generation, boolean profile) {
        return new SolveRequest(
                generation, 300.0,
                profile ? "OLYMPUS M.12-45mm F4.0" : "",
                Collections.singletonList(new SolveRequest.Source(10.0, 20.0, 100.0f)));
    }

    private static SolveResult solution() {
        return new SolveResult(
                12.0, -20.0, 1.0, 3.2,
                1.2, new int[] {0, 1, 2, 3}, new int[] {4, 5});
    }

    private static final class FirstDispatchGate implements Executor {
        private final AtomicInteger dispatches = new AtomicInteger();
        private final List<Runnable> queued = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch firstDispatchEntered = new CountDownLatch(1);
        private final CountDownLatch laterDispatchEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstDispatch = new CountDownLatch(1);

        @Override
        public void execute(Runnable command) {
            if (dispatches.getAndIncrement() == 0) {
                firstDispatchEntered.countDown();
                try {
                    releaseFirstDispatch.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            } else {
                laterDispatchEntered.countDown();
            }
            queued.add(command);
        }

        private void awaitPublicationAttempt(Thread publication) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (laterDispatchEntered.getCount() != 0
                    && publication.getState() != Thread.State.BLOCKED
                    && publication.isAlive()
                    && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }
            assertTrue("publication did not reach dispatch or the coordinator lock",
                    laterDispatchEntered.getCount() == 0
                            || publication.getState() == Thread.State.BLOCKED);
        }

        private void drain() {
            while (true) {
                Runnable next;
                synchronized (queued) {
                    if (queued.isEmpty()) return;
                    next = queued.remove(0);
                }
                next.run();
            }
        }
    }
}
