package dev.omshutter.solver;

/** Immutable ICRS equatorial coordinates derived from a local horizontal pointing. */
public final class EquatorialCoordinates {
    private static final double SINGULAR_PROJECTION = 1.0e-12;

    private final double rightAscensionDeg;
    private final double declinationDeg;

    private EquatorialCoordinates(double rightAscensionDeg, double declinationDeg) {
        this.rightAscensionDeg = rightAscensionDeg;
        this.declinationDeg = declinationDeg;
    }

    /**
     * Inverts {@link HorizontalCoordinates#fromIcrs} using the same mean horizon of date.
     * Longitude is east-positive; azimuth runs clockwise from north and may wrap.
     * An undefined (NaN) azimuth is accepted at zenith or nadir. Right ascension is
     * normalized to [0, 360), or NaN when the resulting ICRS direction is a celestial pole.
     */
    public static EquatorialCoordinates fromHorizontal(
            double altitudeDeg,
            double azimuthDeg,
            double latitudeDeg,
            double longitudeEastDeg,
            long utcEpochSecond,
            int utcNano) {
        requireFinite(altitudeDeg, "altitude");
        requireFinite(latitudeDeg, "latitude");
        requireFinite(longitudeEastDeg, "longitude");
        if (altitudeDeg < -90.0 || altitudeDeg > 90.0) {
            throw new IllegalArgumentException("altitude must be between -90 and 90 degrees");
        }
        if (latitudeDeg < -90.0 || latitudeDeg > 90.0) {
            throw new IllegalArgumentException("latitude must be between -90 and 90 degrees");
        }

        double altitude = Math.toRadians(altitudeDeg);
        double horizontal = Math.cos(altitude);
        boolean vertical = horizontal <= SINGULAR_PROJECTION;
        if (!Double.isFinite(azimuthDeg) && !(vertical && Double.isNaN(azimuthDeg))) {
            throw new IllegalArgumentException("azimuth must be finite unless pointing vertically");
        }
        double azimuth = vertical ? 0.0 : Math.toRadians(normalizeUnsignedDegrees(azimuthDeg));
        double east = vertical ? 0.0 : horizontal * Math.sin(azimuth);
        double north = vertical ? 0.0 : horizontal * Math.cos(azimuth);
        double up = Math.sin(altitude);

        double latitude = Math.toRadians(latitudeDeg);
        double localSidereal = GravityRollEstimator.greenwichMeanSiderealTimeRadians(
                utcEpochSecond, utcNano) + Math.toRadians(normalizeUnsignedDegrees(longitudeEastDeg));
        double sinLatitude = Math.sin(latitude);
        double cosLatitude = Math.cos(latitude);
        double sinSidereal = Math.sin(localSidereal);
        double cosSidereal = Math.cos(localSidereal);

        // Transpose the orthonormal mean-equator-of-date -> east/north/up transform.
        double equatorialPlane = cosLatitude * up - sinLatitude * north;
        double[] ofDate = {
            cosSidereal * equatorialPlane - sinSidereal * east,
            sinSidereal * equatorialPlane + cosSidereal * east,
            sinLatitude * up + cosLatitude * north
        };
        double[][] precession = GravityRollEstimator.icrsToMeanEquatorOfDateMatrix(
                utcEpochSecond, utcNano);
        // A rotation's inverse is its transpose, including the ICRS frame-bias terms.
        double x = precession[0][0] * ofDate[0] + precession[1][0] * ofDate[1]
                + precession[2][0] * ofDate[2];
        double y = precession[0][1] * ofDate[0] + precession[1][1] * ofDate[1]
                + precession[2][1] * ofDate[2];
        double z = precession[0][2] * ofDate[0] + precession[1][2] * ofDate[1]
                + precession[2][2] * ofDate[2];

        double equatorialProjection = Math.hypot(x, y);
        double rightAscension = equatorialProjection <= SINGULAR_PROJECTION
                ? Double.NaN
                : normalizeUnsignedDegrees(Math.toDegrees(Math.atan2(y, x)));
        return new EquatorialCoordinates(
                rightAscension, Math.toDegrees(Math.atan2(z, equatorialProjection)));
    }

    public double getRightAscensionDeg() {
        return rightAscensionDeg;
    }

    public double getDeclinationDeg() {
        return declinationDeg;
    }

    private static double normalizeUnsignedDegrees(double value) {
        double normalized = value % 360.0;
        if (normalized < 0.0) normalized += 360.0;
        // Adding 360 to a tiny negative round-off can round to exactly 360.
        return normalized >= 360.0 || normalized == 0.0 ? 0.0 : normalized;
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }
}
