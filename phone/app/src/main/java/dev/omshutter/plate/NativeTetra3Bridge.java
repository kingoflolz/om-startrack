package dev.omshutter.plate;

import dev.omshutter.solver.PlateSolverSettings;

/** Low-level JNI ABI. Application code should own the returned handle with AutoCloseable. */
public final class NativeTetra3Bridge {
    /** Default cooperative solve deadline used by the Android application. */
    public static final long DEFAULT_SOLVE_TIMEOUT_MILLIS =
            PlateSolverSettings.DEFAULT_TIMEOUT_MILLIS;

    private NativeTetra3Bridge() {}

    private static final class Library {
        static {
            System.loadLibrary("tetra3_android");
        }

        static void ensureLoaded() {}
    }

    /**
     * Maps the exact uncompressed APK asset region and optionally parses a compact lens database.
     * The native side duplicates {@code indexFd}; its AssetFileDescriptor may be closed after this
     * method returns. A null or empty database selects profile-free self-calibration.
     */
    public static long create(
            int indexFd, long assetOffset, long assetLength, byte[] lensDatabase) {
        Library.ensureLoaded();
        return nativeCreate(
                indexFd,
                assetOffset,
                assetLength,
                lensDatabase == null ? new byte[0] : lensDatabase);
    }

    /** Opens an engine with the focal estimate and fitted geometry as its only optical prior. */
    public static long create(int indexFd, long assetOffset, long assetLength) {
        return create(indexFd, assetOffset, assetLength, null);
    }

    /** Idempotently closes a handle. An already-running solve may finish safely. */
    public static void close(long handle) {
        if (handle == 0) return;
        Library.ensureLoaded();
        nativeClose(handle);
    }

    /**
     * Runs one bounded solve. {@code xy} is interleaved encoded-plane X/Y. The raw camera lens
     * name may select a starting profile; missing or ambiguous identity uses profile-free
     * self-calibration from the captured focal estimate.
     */
    public static NativeSolveResult solve(
            long handle,
            String lensName,
            double focalMm,
            double[] xy,
            float[] flux) {
        return solve(
                handle,
                lensName,
                focalMm,
                xy,
                flux,
                DEFAULT_SOLVE_TIMEOUT_MILLIS);
    }

    /**
     * Runs one solve with a configurable monotonic cooperative deadline. A zero timeout is
     * permitted and deterministically requests termination before the first search unit. Deadline
     * expiration is returned as a normal {@link NativeSolveResult}, never as a timeout exception.
     */
    public static NativeSolveResult solve(
            long handle,
            String lensName,
            double focalMm,
            double[] xy,
            float[] flux,
            long timeoutMillis) {
        PlateSolverSettings defaults = PlateSolverSettings.defaults();
        return solveValidated(
                handle,
                lensName,
                focalMm,
                xy,
                flux,
                defaults.getMaxObservations(),
                defaults.getSeedToleranceArcsec(),
                defaults.getMatchToleranceArcsec(),
                defaults.getRequiredIndependentMatches(),
                timeoutMillis);
    }

    /** Runs one solve with an immutable, validated application settings snapshot. */
    public static NativeSolveResult solve(
            long handle,
            String lensName,
            double focalMm,
            double[] xy,
            float[] flux,
            PlateSolverSettings settings) {
        if (settings == null) throw new IllegalArgumentException("settings are null");
        return solveValidated(
                handle,
                lensName,
                focalMm,
                xy,
                flux,
                settings.getMaxObservations(),
                settings.getSeedToleranceArcsec(),
                settings.getMatchToleranceArcsec(),
                settings.getRequiredIndependentMatches(),
                settings.getTimeoutMillis());
    }

    private static NativeSolveResult solveValidated(
            long handle,
            String lensName,
            double focalMm,
            double[] xy,
            float[] flux,
            int maxObservations,
            int seedToleranceArcsec,
            int matchToleranceArcsec,
            int requiredIndependentMatches,
            long timeoutMillis) {
        if (lensName == null) throw new IllegalArgumentException("lensName is null");
        if (xy == null || flux == null) {
            throw new IllegalArgumentException("observation arrays are null");
        }
        if (timeoutMillis < 0L) {
            throw new IllegalArgumentException("timeoutMillis must be nonnegative");
        }
        Library.ensureLoaded();
        return nativeSolve(
                handle,
                lensName,
                focalMm,
                xy,
                flux,
                maxObservations,
                seedToleranceArcsec,
                matchToleranceArcsec,
                requiredIndependentMatches,
                timeoutMillis);
    }

    private static native long nativeCreate(
            int indexFd, long assetOffset, long assetLength, byte[] lensDatabase);

    private static native void nativeClose(long handle);

    private static native NativeSolveResult nativeSolve(
            long handle,
            String lensName,
            double focalMm,
            double[] xy,
            float[] flux,
            int maxObservations,
            int seedToleranceArcsec,
            int matchToleranceArcsec,
            int requiredIndependentMatches,
            long timeoutMillis);
}
