package dev.omshutter.solver;

import java.util.Objects;

/**
 * Immutable, persistable tuning for one local phone plate-solver attempt.
 *
 * <p>The defaults are the existing production tetra3 values. All fields use integral UI/storage
 * units so a load/save cycle cannot perturb the native floating-point defaults. Four pattern
 * observations are separate from {@link #requiredIndependentMatches}.
 */
public final class PlateSolverSettings {
    public static final int MIN_MAX_OBSERVATIONS = 8;
    /**
     * STAR and JNI transport at most 256 sources. Native pattern acquisition
     * admits the fainter tail lazily, while online verification cumulatively
     * uses this complete brightness-ordered population.
     */
    public static final int MAX_MAX_OBSERVATIONS = SolveRequest.MAX_SOURCES;
    public static final int DEFAULT_MAX_OBSERVATIONS = MAX_MAX_OBSERVATIONS;

    public static final int MIN_TOLERANCE_ARCSEC = 15;
    public static final int MAX_TOLERANCE_ARCSEC = 300;
    public static final int DEFAULT_SEED_TOLERANCE_ARCSEC = 90;
    public static final int DEFAULT_MATCH_TOLERANCE_ARCSEC = 75;

    public static final int MIN_REQUIRED_INDEPENDENT_MATCHES = 1;
    public static final int MAX_REQUIRED_INDEPENDENT_MATCHES = 16;
    public static final int DEFAULT_REQUIRED_INDEPENDENT_MATCHES = 4;

    public static final int MIN_TIMEOUT_MILLIS = 100;
    public static final int MAX_TIMEOUT_MILLIS = 10_000;
    public static final int DEFAULT_TIMEOUT_MILLIS = 1_000;

    private static final PlateSolverSettings DEFAULTS = new PlateSolverSettings(
            DEFAULT_MAX_OBSERVATIONS,
            DEFAULT_SEED_TOLERANCE_ARCSEC,
            DEFAULT_MATCH_TOLERANCE_ARCSEC,
            DEFAULT_REQUIRED_INDEPENDENT_MATCHES,
            DEFAULT_TIMEOUT_MILLIS);

    private final int maxObservations;
    private final int seedToleranceArcsec;
    private final int matchToleranceArcsec;
    private final int requiredIndependentMatches;
    private final int timeoutMillis;

    public PlateSolverSettings(
            int maxObservations,
            int seedToleranceArcsec,
            int matchToleranceArcsec,
            int requiredIndependentMatches,
            int timeoutMillis) {
        requireRange(
                maxObservations,
                MIN_MAX_OBSERVATIONS,
                MAX_MAX_OBSERVATIONS,
                "max observations");
        requireRange(
                seedToleranceArcsec,
                MIN_TOLERANCE_ARCSEC,
                MAX_TOLERANCE_ARCSEC,
                "seed tolerance");
        requireRange(
                matchToleranceArcsec,
                MIN_TOLERANCE_ARCSEC,
                MAX_TOLERANCE_ARCSEC,
                "match tolerance");
        requireRange(
                requiredIndependentMatches,
                MIN_REQUIRED_INDEPENDENT_MATCHES,
                MAX_REQUIRED_INDEPENDENT_MATCHES,
                "required independent matches");
        if (requiredIndependentMatches > maxObservations - 4) {
            throw new IllegalArgumentException(
                    "required independent matches do not fit outside the four-star seed");
        }
        requireRange(
                timeoutMillis,
                MIN_TIMEOUT_MILLIS,
                MAX_TIMEOUT_MILLIS,
                "solve timeout");
        this.maxObservations = maxObservations;
        this.seedToleranceArcsec = seedToleranceArcsec;
        this.matchToleranceArcsec = matchToleranceArcsec;
        this.requiredIndependentMatches = requiredIndependentMatches;
        this.timeoutMillis = timeoutMillis;
    }

    public static PlateSolverSettings defaults() {
        return DEFAULTS;
    }

    public int getMaxObservations() { return maxObservations; }
    public int getSeedToleranceArcsec() { return seedToleranceArcsec; }
    public int getMatchToleranceArcsec() { return matchToleranceArcsec; }
    public int getRequiredIndependentMatches() { return requiredIndependentMatches; }
    public int getTimeoutMillis() { return timeoutMillis; }

    public PlateSolverSettings withMaxObservations(int value) {
        return new PlateSolverSettings(
                value,
                seedToleranceArcsec,
                matchToleranceArcsec,
                requiredIndependentMatches,
                timeoutMillis);
    }

    public PlateSolverSettings withSeedToleranceArcsec(int value) {
        return new PlateSolverSettings(
                maxObservations,
                value,
                matchToleranceArcsec,
                requiredIndependentMatches,
                timeoutMillis);
    }

    public PlateSolverSettings withMatchToleranceArcsec(int value) {
        return new PlateSolverSettings(
                maxObservations,
                seedToleranceArcsec,
                value,
                requiredIndependentMatches,
                timeoutMillis);
    }

    public PlateSolverSettings withRequiredIndependentMatches(int value) {
        return new PlateSolverSettings(
                maxObservations,
                seedToleranceArcsec,
                matchToleranceArcsec,
                value,
                timeoutMillis);
    }

    public PlateSolverSettings withTimeoutMillis(int value) {
        return new PlateSolverSettings(
                maxObservations,
                seedToleranceArcsec,
                matchToleranceArcsec,
                requiredIndependentMatches,
                value);
    }

    private static void requireRange(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    field + " is outside " + minimum + ".." + maximum);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PlateSolverSettings)) return false;
        PlateSolverSettings that = (PlateSolverSettings) other;
        return maxObservations == that.maxObservations
                && seedToleranceArcsec == that.seedToleranceArcsec
                && matchToleranceArcsec == that.matchToleranceArcsec
                && requiredIndependentMatches == that.requiredIndependentMatches
                && timeoutMillis == that.timeoutMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                maxObservations,
                seedToleranceArcsec,
                matchToleranceArcsec,
                requiredIndependentMatches,
                timeoutMillis);
    }

    @Override
    public String toString() {
        return "PlateSolverSettings{maxObservations=" + maxObservations
                + ", seedToleranceArcsec=" + seedToleranceArcsec
                + ", matchToleranceArcsec=" + matchToleranceArcsec
                + ", requiredIndependentMatches=" + requiredIndependentMatches
                + ", timeoutMillis=" + timeoutMillis + "}";
    }
}
