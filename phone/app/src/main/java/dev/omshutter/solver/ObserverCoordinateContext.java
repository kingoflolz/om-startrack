package dev.omshutter.solver;

/**
 * Ephemeral observer fix plus trusted UTC anchor used to update one solved pointing.
 * This value is intentionally suitable for in-memory UI state only.
 */
public final class ObserverCoordinateContext {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    public static final class UtcInstant {
        private final long epochSecond;
        private final int nano;

        private UtcInstant(long epochSecond, int nano) {
            this.epochSecond = epochSecond;
            this.nano = nano;
        }

        public long getEpochSecond() {
            return epochSecond;
        }

        public int getNano() {
            return nano;
        }
    }

    private final double latitudeDeg;
    private final double longitudeEastDeg;
    private final long utcEpochSecond;
    private final int utcNano;
    private final long utcAnchorElapsedRealtimeNanos;
    private final long locationFixElapsedRealtimeNanos;

    private ObserverCoordinateContext(
            double latitudeDeg,
            double longitudeEastDeg,
            long utcEpochSecond,
            int utcNano,
            long utcAnchorElapsedRealtimeNanos,
            long locationFixElapsedRealtimeNanos) {
        this.latitudeDeg = latitudeDeg;
        this.longitudeEastDeg = longitudeEastDeg;
        this.utcEpochSecond = utcEpochSecond;
        this.utcNano = utcNano;
        this.utcAnchorElapsedRealtimeNanos = utcAnchorElapsedRealtimeNanos;
        this.locationFixElapsedRealtimeNanos = locationFixElapsedRealtimeNanos;
    }

    public static ObserverCoordinateContext of(
            double latitudeDeg,
            double longitudeEastDeg,
            long utcEpochSecond,
            int utcNano,
            long utcAnchorElapsedRealtimeNanos) {
        return of(latitudeDeg, longitudeEastDeg, utcEpochSecond, utcNano,
                utcAnchorElapsedRealtimeNanos, utcAnchorElapsedRealtimeNanos);
    }

    /** Retains the actual location-fix timestamp separately from the trusted UTC receipt time. */
    public static ObserverCoordinateContext of(
            double latitudeDeg,
            double longitudeEastDeg,
            long utcEpochSecond,
            int utcNano,
            long utcAnchorElapsedRealtimeNanos,
            long locationFixElapsedRealtimeNanos) {
        if (!Double.isFinite(latitudeDeg) || latitudeDeg < -90.0 || latitudeDeg > 90.0) {
            throw new IllegalArgumentException("latitude is invalid");
        }
        if (!Double.isFinite(longitudeEastDeg)) {
            throw new IllegalArgumentException("longitude is invalid");
        }
        if (utcNano < 0 || utcNano >= NANOS_PER_SECOND) {
            throw new IllegalArgumentException("UTC nanosecond is outside 0..999999999");
        }
        if (utcAnchorElapsedRealtimeNanos < 0L) {
            throw new IllegalArgumentException("UTC anchor timestamp is negative");
        }
        if (locationFixElapsedRealtimeNanos < 0L
                || locationFixElapsedRealtimeNanos > utcAnchorElapsedRealtimeNanos) {
            throw new IllegalArgumentException("location fix timestamp is outside 0..UTC anchor");
        }
        return new ObserverCoordinateContext(
                latitudeDeg,
                normalizeSignedDegrees(longitudeEastDeg),
                utcEpochSecond,
                utcNano,
                utcAnchorElapsedRealtimeNanos,
                locationFixElapsedRealtimeNanos);
    }

    public double getLatitudeDeg() {
        return latitudeDeg;
    }

    public double getLongitudeEastDeg() {
        return longitudeEastDeg;
    }

    public long getLocationFixElapsedRealtimeNanos() {
        return locationFixElapsedRealtimeNanos;
    }

    /**
     * Maps a capture's monotonic timestamp through this trusted UTC fix, including captures
     * preceding location acquisition. The caller owns capture/source-clock association.
     */
    public UtcInstant utcAtCaptureElapsed(long captureElapsedRealtimeNanos) {
        if (captureElapsedRealtimeNanos < 0L) {
            throw new IllegalArgumentException("capture timestamp is negative");
        }
        long deltaNanos = Math.subtractExact(
                captureElapsedRealtimeNanos, utcAnchorElapsedRealtimeNanos);
        long seconds = Math.floorDiv(deltaNanos, NANOS_PER_SECOND);
        long nanos = utcNano + Math.floorMod(deltaNanos, NANOS_PER_SECOND);
        return new UtcInstant(
                Math.addExact(Math.addExact(utcEpochSecond, seconds),
                        nanos / NANOS_PER_SECOND),
                (int) (nanos % NANOS_PER_SECOND));
    }

    public HorizontalCoordinates horizontalAt(
            SolveResult solve, long nowElapsedRealtimeNanos) {
        if (solve == null) throw new IllegalArgumentException("plate solution is unavailable");
        UtcInstant utc = utcAt(nowElapsedRealtimeNanos);
        return HorizontalCoordinates.fromIcrs(
                solve.getRightAscensionDeg(),
                solve.getDeclinationDeg(),
                latitudeDeg,
                longitudeEastDeg,
                utc.epochSecond,
                utc.nano);
    }

    /** Converts a current local pointing to ICRS using this observer fix and trusted clock. */
    public EquatorialCoordinates equatorialAt(
            double altitudeDeg, double azimuthDeg, long nowElapsedRealtimeNanos) {
        UtcInstant utc = utcAt(nowElapsedRealtimeNanos);
        return EquatorialCoordinates.fromHorizontal(
                altitudeDeg,
                azimuthDeg,
                latitudeDeg,
                longitudeEastDeg,
                utc.epochSecond,
                utc.nano);
    }

    public UtcInstant utcAt(long nowElapsedRealtimeNanos) {
        if (nowElapsedRealtimeNanos < utcAnchorElapsedRealtimeNanos) {
            throw new IllegalArgumentException("elapsed realtime precedes UTC anchor");
        }
        long deltaNanos = nowElapsedRealtimeNanos - utcAnchorElapsedRealtimeNanos;
        long deltaSeconds = deltaNanos / NANOS_PER_SECOND;
        long remainderNanos = deltaNanos % NANOS_PER_SECOND;
        long summedNanos = utcNano + remainderNanos;
        long carry = summedNanos / NANOS_PER_SECOND;
        long advancedSecond = Math.addExact(
                Math.addExact(utcEpochSecond, deltaSeconds), carry);
        return new UtcInstant(advancedSecond, (int) (summedNanos % NANOS_PER_SECOND));
    }

    private static double normalizeSignedDegrees(double value) {
        double normalized = value % 360.0;
        if (normalized < -180.0) normalized += 360.0;
        if (normalized >= 180.0) normalized -= 360.0;
        return normalized;
    }
}
