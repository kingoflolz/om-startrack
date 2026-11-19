package dev.omshutter.solver;

import dev.omshutter.protocol.StarCaptureProtocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable, bounded input for one plate-solver attempt. */
public final class SolveRequest {
    public static final int MAX_SOURCES = 256;
    public static final int STAR_WIDTH = 1296;
    public static final int STAR_HEIGHT = 972;

    public static final class Source {
        private final double x;
        private final double y;
        private final float flux;

        public Source(double x, double y, float flux) {
            if (!Double.isFinite(x) || x < 0.0 || x >= STAR_WIDTH
                    || !Double.isFinite(y) || y < 0.0 || y >= STAR_HEIGHT) {
                throw new IllegalArgumentException("source coordinates are outside 1296x972");
            }
            if (!Float.isFinite(flux) || flux <= 0.0f) {
                throw new IllegalArgumentException("source flux is invalid");
            }
            this.x = x;
            this.y = y;
            this.flux = flux;
        }

        public double getX() { return x; }
        public double getY() { return y; }
        public float getFlux() { return flux; }
    }

    private final long generation;
    private final double focalLengthMm;
    private final String lensName;
    private final List<Source> sources;
    private final PlateSolverSettings solverSettings;

    public SolveRequest(
            long generation,
            double focalLengthMm,
            String lensName,
            List<Source> sources) {
        this(
                generation,
                focalLengthMm,
                lensName,
                sources,
                PlateSolverSettings.defaults());
    }

    public SolveRequest(
            long generation,
            double focalLengthMm,
            String lensName,
            List<Source> sources,
            PlateSolverSettings solverSettings) {
        if (generation < 0) {
            throw new IllegalArgumentException("solve generation is negative");
        }
        if (!Double.isFinite(focalLengthMm)
                || focalLengthMm < 0.1
                || focalLengthMm > 2047.96875) {
            throw new IllegalArgumentException("focal length is outside the camera domain");
        }
        if (sources == null || sources.size() > MAX_SOURCES) {
            throw new IllegalArgumentException("too many STAR sources");
        }
        if (solverSettings == null) {
            throw new IllegalArgumentException("plate-solver settings are null");
        }
        ArrayList<Source> copied = new ArrayList<>(sources.size());
        for (Source source : sources) {
            if (source == null) throw new IllegalArgumentException("null STAR source");
            copied.add(source);
        }
        this.generation = generation;
        this.focalLengthMm = focalLengthMm;
        this.lensName = checkedLensName(lensName);
        this.sources = Collections.unmodifiableList(copied);
        this.solverSettings = solverSettings;
    }

    public long getGeneration() { return generation; }
    public double getFocalLengthMm() { return focalLengthMm; }
    public String getLensName() { return lensName; }
    public boolean hasLensMetadata() { return !lensName.isEmpty(); }
    public List<Source> getSources() { return sources; }
    public PlateSolverSettings getSolverSettings() { return solverSettings; }

    private static String checkedLensName(String value) {
        String checked = (value == null ? "" : value).trim();
        if (checked.length() > StarCaptureProtocol.MAX_LENS_NAME_BYTES
                || checked.chars().anyMatch(character -> character < 0x20 || character > 0x7e)) {
            throw new IllegalArgumentException("lens name is invalid");
        }
        return checked;
    }
}
