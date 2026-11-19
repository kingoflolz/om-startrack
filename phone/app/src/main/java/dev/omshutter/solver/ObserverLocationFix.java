package dev.omshutter.solver;

import java.util.Objects;

/** Immutable phone location used to relate a celestial pointing to local vertical. */
public final class ObserverLocationFix {
    public static final long MAX_AGE_NANOS = 300_000_000_000L;
    public static final double MAX_ACCURACY_METERS = 2_000.0;

    private final double latitudeDeg;
    private final double longitudeEastDeg;
    private final double accuracyMeters;
    private final long fixElapsedRealtimeNanos;

    private ObserverLocationFix(
            double latitudeDeg,
            double longitudeEastDeg,
            double accuracyMeters,
            long fixElapsedRealtimeNanos) {
        this.latitudeDeg = latitudeDeg;
        this.longitudeEastDeg = longitudeEastDeg;
        this.accuracyMeters = accuracyMeters;
        this.fixElapsedRealtimeNanos = fixElapsedRealtimeNanos;
    }

    public static ObserverLocationFix of(
            double latitudeDeg,
            double longitudeEastDeg,
            double accuracyMeters,
            long fixElapsedRealtimeNanos) {
        requireFinite(latitudeDeg, "latitude");
        requireFinite(longitudeEastDeg, "longitude");
        requireFinite(accuracyMeters, "location accuracy");
        if (latitudeDeg < -90.0 || latitudeDeg > 90.0) {
            throw new IllegalArgumentException("latitude must be between -90 and 90 degrees");
        }
        if (accuracyMeters < 0.0) {
            throw new IllegalArgumentException("location accuracy is negative");
        }
        if (fixElapsedRealtimeNanos < 0L) {
            throw new IllegalArgumentException("location timestamp is negative");
        }
        return new ObserverLocationFix(
                latitudeDeg,
                normalizeSignedDegrees(longitudeEastDeg),
                accuracyMeters,
                fixElapsedRealtimeNanos);
    }

    public double getLatitudeDeg() {
        return latitudeDeg;
    }

    /** Longitude in degrees east of Greenwich, normalized to [-180, 180). */
    public double getLongitudeEastDeg() {
        return longitudeEastDeg;
    }

    public double getAccuracyMeters() {
        return accuracyMeters;
    }

    public long getFixElapsedRealtimeNanos() {
        return fixElapsedRealtimeNanos;
    }

    public boolean isQualifiedAt(long elapsedRealtimeNanos) {
        if (elapsedRealtimeNanos < fixElapsedRealtimeNanos) {
            throw new IllegalArgumentException("elapsed realtime precedes location timestamp");
        }
        return accuracyMeters <= MAX_ACCURACY_METERS
                && elapsedRealtimeNanos - fixElapsedRealtimeNanos <= MAX_AGE_NANOS;
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    private static double normalizeSignedDegrees(double value) {
        double normalized = value % 360.0;
        if (normalized < -180.0) normalized += 360.0;
        if (normalized >= 180.0) normalized -= 360.0;
        return normalized;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ObserverLocationFix)) return false;
        ObserverLocationFix that = (ObserverLocationFix) other;
        return Double.compare(latitudeDeg, that.latitudeDeg) == 0
                && Double.compare(longitudeEastDeg, that.longitudeEastDeg) == 0
                && Double.compare(accuracyMeters, that.accuracyMeters) == 0
                && fixElapsedRealtimeNanos == that.fixElapsedRealtimeNanos;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                latitudeDeg,
                longitudeEastDeg,
                accuracyMeters,
                fixElapsedRealtimeNanos);
    }
}
