package dev.omshutter.solver;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Deduplicates validated capture generations and confines initialization and solving to one
 * bounded background executor.
 */
public final class PlateSolverCoordinator implements AutoCloseable {
    public interface Listener {
        void onSolveChanged(SolveSnapshot snapshot);
    }

    private final Object lock = new Object();
    private final SolverEngineFactory engineFactory;
    private final Executor listenerExecutor;
    private final ThreadPoolExecutor worker;
    private final LongSupplier monotonicNanos;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile SolveSnapshot snapshot = SolveSnapshot.idle();
    private SolverEngine engine;
    private long lastAcceptedGeneration = -1;
    private long lifecycleVersion;
    private boolean closed;

    /**
     * @param listenerExecutor ordered dispatcher for lightweight callbacks; Android supplies its
     *                         main-handler executor
     */
    public PlateSolverCoordinator(
            SolverEngineFactory engineFactory, Executor listenerExecutor) {
        this(engineFactory, listenerExecutor, System::nanoTime);
    }

    PlateSolverCoordinator(
            SolverEngineFactory engineFactory,
            Executor listenerExecutor,
            LongSupplier monotonicNanos) {
        if (engineFactory == null || listenerExecutor == null || monotonicNanos == null) {
            throw new IllegalArgumentException("solver dependencies are null");
        }
        this.engineFactory = engineFactory;
        this.listenerExecutor = listenerExecutor;
        this.monotonicNanos = monotonicNanos;
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "om-plate-solver");
            thread.setDaemon(true);
            return thread;
        };
        worker = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public SolveSnapshot getSnapshot() { return snapshot; }

    public void addListener(Listener listener) {
        if (listener == null) return;
        synchronized (lock) {
            // Registration and its initial replay must be ordered with every
            // publication. Otherwise a worker can enqueue a newer callback
            // after this snapshot is read but before the replay is enqueued.
            listeners.add(listener);
            dispatch(listener, snapshot);
        }
    }

    public void removeListener(Listener listener) { listeners.remove(listener); }

    /** Returns false for a duplicate, stale, closed, or bounded-queue-rejected generation. */
    public boolean submit(SolveRequest request) {
        if (request == null) throw new IllegalArgumentException("solve request is null");
        synchronized (lock) {
            if (closed || request.getGeneration() <= lastAcceptedGeneration) return false;
            lastAcceptedGeneration = request.getGeneration();
            long version = lifecycleVersion;
            try {
                // Enqueue while holding the lifecycle lock so invalidate cannot clear the queue
                // and then race a stale task back into it.
                worker.execute(() -> runSolve(request, version));
            } catch (RejectedExecutionException error) {
                SolveSnapshot next = SolveSnapshot.of(
                        SolveSnapshot.State.ERROR,
                        request.getGeneration(), null,
                        "Plate solver is busy; generation was not queued",
                        !request.hasLensMetadata(), !request.hasLensMetadata());
                snapshot = next;
                notifyListeners(next);
                return false;
            }
            SolveSnapshot next = SolveSnapshot.of(
                    SolveSnapshot.State.QUEUED,
                    request.getGeneration(), null,
                    request.hasLensMetadata()
                            ? "Plate solve queued; resolving optional lens prior"
                            : "Plate solve queued; using profile-free self-calibration",
                    !request.hasLensMetadata(), !request.hasLensMetadata());
            snapshot = next;
            notifyListeners(next);
            return true;
        }
    }

    /** Records a validated generation that cannot be solved because focal metadata is absent. */
    public boolean submitMissingFocal(long generation, boolean lensMetadataMissing) {
        long version;
        synchronized (lock) {
            if (closed || generation <= lastAcceptedGeneration) return false;
            lastAcceptedGeneration = generation;
            version = lifecycleVersion;
        }
        publishIfCurrent(SolveSnapshot.of(
                SolveSnapshot.State.MISSING_METADATA,
                generation, null,
                lensMetadataMissing
                        ? "Plate solve unavailable: focal length and lens identity are missing"
                        : "Plate solve unavailable: captured focal length is missing",
                lensMetadataMissing, true), version);
        return true;
    }

    /** Records a rejected validated generation without invoking the native engine. */
    public boolean submitError(
            long generation, String detail, boolean lensMetadataMissing) {
        long version;
        synchronized (lock) {
            if (closed || generation <= lastAcceptedGeneration) return false;
            lastAcceptedGeneration = generation;
            version = lifecycleVersion;
        }
        String message = detail == null || detail.isEmpty()
                ? "Plate-solver input was invalid" : detail;
        publishIfCurrent(SolveSnapshot.of(
                SolveSnapshot.State.ERROR,
                generation, null, message,
                lensMetadataMissing, lensMetadataMissing), version);
        return true;
    }

    /**
     * Invalidates running and queued publications without blocking for a native solve to stop.
     * The native engine remains reusable for a later camera generation.
     */
    public void invalidate(String detail) {
        synchronized (lock) {
            if (closed) return;
            lifecycleVersion++;
            worker.getQueue().clear();
            SolveSnapshot next = SolveSnapshot.of(
                    SolveSnapshot.State.IDLE,
                    Math.max(lastAcceptedGeneration, snapshot.getGeneration()),
                    null,
                    detail == null ? "" : detail,
                    false, false);
            snapshot = next;
            notifyListeners(next);
        }
    }

    private void runSolve(SolveRequest request, long version) {
        if (!isCurrent(version)) return;
        publishIfCurrent(SolveSnapshot.of(
                SolveSnapshot.State.SOLVING,
                request.getGeneration(), null,
                request.hasLensMetadata()
                        ? "Solving star field; resolving optional lens prior"
                        : "Solving star field with profile-free self-calibration",
                !request.hasLensMetadata(), !request.hasLensMetadata()), version);
        long solveStartedNanos = monotonicNanos.getAsLong();
        try {
            SolverEngine activeEngine = getOrCreateEngine();
            if (activeEngine == null) return;
            if (!isCurrent(version)) return;
            SolverEngine.Outcome outcome = activeEngine.solve(request);
            if (outcome == null) throw new IllegalStateException("solver returned no outcome");
            boolean fallback = outcome.isDistortionFallback();
            boolean lensMissing = !request.hasLensMetadata();
            long solveDurationNanos = elapsedNanos(
                    solveStartedNanos, monotonicNanos.getAsLong());
            if (outcome.getKind() == SolverEngine.Outcome.Kind.SOLVED) {
                publishIfCurrent(SolveSnapshot.terminal(
                        SolveSnapshot.State.SOLVED,
                        request.getGeneration(), outcome.getResult(),
                        decorateDetail(outcome.getDetail(), lensMissing, fallback),
                        lensMissing, fallback, solveDurationNanos), version);
            } else {
                publishIfCurrent(SolveSnapshot.terminal(
                        SolveSnapshot.State.NO_MATCH,
                        request.getGeneration(), null,
                        decorateDetail(outcome.getDetail(), lensMissing, fallback),
                        lensMissing, fallback, solveDurationNanos), version);
            }
        } catch (Exception | LinkageError error) {
            publishIfCurrent(SolveSnapshot.terminal(
                    SolveSnapshot.State.ERROR,
                    request.getGeneration(), null,
                    errorMessage(error),
                    !request.hasLensMetadata(), !request.hasLensMetadata(),
                    elapsedNanos(solveStartedNanos, monotonicNanos.getAsLong())), version);
        }
    }

    static long elapsedNanos(long startedNanos, long finishedNanos) {
        // System.nanoTime differences are valid across signed wraparound. A custom clock used by
        // tests must still be monotonic; clamp a regression so presentation metadata stays valid.
        long elapsed = finishedNanos - startedNanos;
        return elapsed < 0L ? 0L : elapsed;
    }

    private SolverEngine getOrCreateEngine() throws Exception {
        synchronized (lock) {
            if (closed) return null;
            if (engine != null) return engine;
        }
        SolverEngine created = engineFactory.create();
        if (created == null) throw new IllegalStateException("solver engine factory returned null");
        synchronized (lock) {
            if (closed) {
                created.close();
                return null;
            }
            engine = created;
            return engine;
        }
    }

    private static String decorateDetail(String detail, boolean lensMissing, boolean fallback) {
        String base = detail == null || detail.isEmpty() ? "Plate solve completed" : detail;
        // Missing or unresolved lens identity is no longer a degraded solve:
        // focal-plus-radial self-calibration is the complete fallback model.
        return base;
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) message = error.getClass().getSimpleName();
        return "Plate solver failed: " + message;
    }

    private boolean isCurrent(long version) {
        synchronized (lock) { return !closed && lifecycleVersion == version; }
    }

    private void publishIfCurrent(SolveSnapshot next, long version) {
        synchronized (lock) {
            if (closed || lifecycleVersion != version
                    || next.getGeneration() < snapshot.getGeneration()) return;
            snapshot = next;
            notifyListeners(next);
        }
    }

    private void publish(SolveSnapshot next) {
        synchronized (lock) {
            snapshot = next;
            notifyListeners(next);
        }
    }

    private void notifyListeners(SolveSnapshot next) {
        for (Listener listener : listeners) dispatch(listener, next);
    }

    private void dispatch(Listener listener, SolveSnapshot next) {
        try {
            listenerExecutor.execute(() -> listener.onSolveChanged(next));
        } catch (RuntimeException ignored) {
            // A shutting-down UI dispatcher must not take down the solver worker.
        }
    }

    @Override
    public void close() {
        SolverEngine activeEngine;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            lifecycleVersion++;
            activeEngine = engine;
            engine = null;
        }
        worker.shutdownNow();
        if (activeEngine != null) {
            try {
                activeEngine.close();
            } catch (RuntimeException | LinkageError ignored) {
                // CLOSED must remain observable even if native teardown reports a failure.
            }
        }
        publish(SolveSnapshot.of(
                SolveSnapshot.State.CLOSED,
                snapshot.getGeneration(), null,
                "Plate solver closed", false, false));
    }
}
