package dev.omshutter.solver;

import dev.omshutter.protocol.StarCaptureSession;
import dev.omshutter.protocol.StarCaptureProtocol;

import java.util.ArrayList;
import java.util.List;

/** Copies one fully validated STAR publication into a solver-owned bounded request. */
public final class StarSolveRequestAdapter {
    public static final class Adapted {
        private final SolveRequest request;
        private final boolean lensMetadataMissing;

        private Adapted(SolveRequest request, boolean lensMetadataMissing) {
            this.request = request;
            this.lensMetadataMissing = lensMetadataMissing;
        }

        public SolveRequest getRequest() { return request; }
        public boolean isLensMetadataMissing() { return lensMetadataMissing; }
    }

    private StarSolveRequestAdapter() {}

    public static Adapted adapt(long generation, StarCaptureSession.Snapshot snapshot) {
        return adapt(generation, snapshot, PlateSolverSettings.defaults());
    }

    public static Adapted adapt(
            long generation,
            StarCaptureSession.Snapshot snapshot,
            PlateSolverSettings solverSettings) {
        if (generation < 0) throw new IllegalArgumentException("solve generation is negative");
        if (snapshot == null || !snapshot.isSolveReady()) {
            throw new IllegalArgumentException("STAR generation is not fully validated");
        }
        if (solverSettings == null) {
            throw new IllegalArgumentException("plate-solver settings are null");
        }
        StarCaptureProtocol.CaptureMetadata metadata = snapshot.getCapturedMetadata();
        String lensName = metadata.getLensName().trim();
        boolean lensMissing = lensName.isEmpty();
        if (!metadata.hasFocalLength()) {
            return new Adapted(null, lensMissing);
        }
        List<StarCaptureProtocol.Peak> peaks = snapshot.getPeaks();
        ArrayList<SolveRequest.Source> sources = new ArrayList<>(peaks.size());
        for (StarCaptureProtocol.Peak peak : peaks) {
            sources.add(new SolveRequest.Source(
                    peak.getX(), peak.getY(), (float) peak.getFlux()));
        }
        SolveRequest request = new SolveRequest(
                generation,
                metadata.getFocalLengthMm(),
                lensName,
                sources,
                solverSettings);
        return new Adapted(request, lensMissing);
    }
}
