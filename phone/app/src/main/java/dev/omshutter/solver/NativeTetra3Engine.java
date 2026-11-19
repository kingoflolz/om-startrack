package dev.omshutter.solver;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;

import dev.omshutter.plate.NativeSolution;
import dev.omshutter.plate.NativeSolveResult;
import dev.omshutter.plate.NativeTetra3Bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Android asset owner and high-level adapter for the Rust JNI solver. */
public final class NativeTetra3Engine implements SolverEngine {
    private static final String INDEX_ASSET = "tetra3/om1-300f4-to-45.t3ix";
    private static final String LENS_ASSET = "tetra3/om1-all-mft.t3lf";
    private static final int MAX_LENS_DATABASE_BYTES = 64 * 1024 * 1024;

    private final AtomicLong handle;

    /**
     * Opens and validates the packaged databases. The coordinator invokes this constructor only
     * on its dedicated worker, never on Android's main or BLE threads.
     */
    public NativeTetra3Engine(Context context) throws IOException {
        if (context == null) throw new IllegalArgumentException("context is null");
        AssetManager assets = context.getAssets();
        // Lensfun data is only a starting prior. A build which deliberately
        // omits it must retain the complete profile-free solve path.
        byte[] lensDatabase = readOptionalBoundedAsset(assets, LENS_ASSET);
        long created;
        try (AssetFileDescriptor index = assets.openFd(INDEX_ASSET)) {
            long length = index.getLength();
            long offset = index.getStartOffset();
            if (length <= 0L || offset < 0L) {
                throw new IOException("Plate-solver index is not an uncompressed asset region");
            }
            created = NativeTetra3Bridge.create(
                    index.getParcelFileDescriptor().getFd(), offset, length, lensDatabase);
        }
        if (created <= 0L) {
            throw new IOException("Native plate solver returned an invalid handle");
        }
        handle = new AtomicLong(created);
    }

    @Override
    public Outcome solve(SolveRequest request) {
        if (request == null) throw new IllegalArgumentException("solve request is null");
        long activeHandle = handle.get();
        if (activeHandle == 0L) throw new IllegalStateException("plate solver is closed");

        List<SolveRequest.Source> sources = request.getSources();
        double[] xy = new double[sources.size() * 2];
        float[] flux = new float[sources.size()];
        for (int index = 0; index < sources.size(); index++) {
            SolveRequest.Source source = sources.get(index);
            xy[index * 2] = source.getX();
            xy[index * 2 + 1] = source.getY();
            flux[index] = source.getFlux();
        }
        NativeSolveResult nativeResult = NativeTetra3Bridge.solve(
                activeHandle,
                request.getLensName(),
                request.getFocalLengthMm(),
                xy,
                flux,
                request.getSolverSettings());
        if (nativeResult == null) {
            throw new IllegalStateException("native plate solver returned null");
        }

        boolean fallback = nativeResult.usedRectilinearFallback();
        switch (nativeResult.getStatus()) {
            case NativeSolveResult.STATUS_SOLVED:
                return Outcome.solved(
                        adaptSolution(nativeResult.getSolution(), request),
                        solvedDetail(nativeResult),
                        fallback);
            case NativeSolveResult.STATUS_NO_MATCH:
                return Outcome.noMatch(noMatchDetail(nativeResult), fallback);
            case NativeSolveResult.STATUS_AMBIGUOUS:
                return Outcome.noMatch(ambiguousDetail(nativeResult), fallback);
            default:
                throw new IllegalStateException(
                        "native plate solver returned an unknown status");
        }
    }

    static SolveResult adaptSolution(NativeSolution nativeSolution, SolveRequest request) {
        if (nativeSolution == null) {
            throw new IllegalArgumentException("native solved result lacks a solution");
        }
        if (request == null) throw new IllegalArgumentException("solve request is unavailable");
        return new SolveResult(
                nativeSolution.getCenterRaDeg(),
                nativeSolution.getCenterDecDeg(),
                nativeSolution.getRollDeg(),
                nativeSolution.getHorizontalFovDeg(),
                nativeSolution.getRmsResidualArcsec(),
                nativeSolution.getPatternSourceIndices(),
                nativeSolution.getMatchedSourceIndices(),
                PlanarFocalCalibration.of(request.getFocalLengthMm(),
                        nativeSolution.getSolvedFocalLengthMm()));
    }

    private static String solvedDetail(NativeSolveResult result) {
        return calibrationDetail(result.getSelectedProfileId());
    }

    static String calibrationDetail(String profile) {
        return profile == null
                ? "Plate solved with profile-free self-calibration"
                : "Plate solved with profile-assisted self-calibration " + profile;
    }

    private static String noMatchDetail(NativeSolveResult result) {
        return "No plate solution: " + noMatchReasonDetail(result.getNoMatchReason());
    }

    static String noMatchReasonDetail(int noMatchReason) {
        switch (noMatchReason) {
            case NativeSolveResult.NO_MATCH_INSUFFICIENT_OBSERVATIONS:
                return "too few usable star observations";
            case NativeSolveResult.NO_MATCH_SEARCH_EXHAUSTED:
                return "the index search found no matching field";
            case NativeSolveResult.NO_MATCH_REJECTED_BY_VERIFICATION:
                return "candidate fields failed independent verification";
            case NativeSolveResult.NO_MATCH_BUDGET_EXHAUSTED:
                return "the bounded search budget was exhausted";
            case NativeSolveResult.NO_MATCH_DEADLINE_EXCEEDED:
                return "solve deadline expired before a verified field was found";
            default:
                return "no reliable field matched";
        }
    }

    private static String ambiguousDetail(NativeSolveResult result) {
        double[] probabilities = result.getAmbiguityChanceProbabilities();
        if (probabilities.length != 2) {
            return "No plate solution: native result was ambiguous";
        }
        return "No plate solution: two fields remained ambiguous (chance "
                + probabilities[0] + " versus " + probabilities[1] + ")";
    }

    @Override
    public void close() {
        long oldHandle = handle.getAndSet(0L);
        if (oldHandle != 0L) NativeTetra3Bridge.close(oldHandle);
    }

    private static byte[] readOptionalBoundedAsset(AssetManager assets, String path)
            throws IOException {
        InputStream opened;
        try {
            opened = assets.open(path, AssetManager.ACCESS_STREAMING);
        } catch (IOException missing) {
            return new byte[0];
        }
        try (InputStream input = opened;
             ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024)) {
            byte[] buffer = new byte[8192];
            int total = 0;
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                if (count == 0) continue;
                total += count;
                if (total > MAX_LENS_DATABASE_BYTES) {
                    throw new IOException("Lens database exceeds the 64 MiB format limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
