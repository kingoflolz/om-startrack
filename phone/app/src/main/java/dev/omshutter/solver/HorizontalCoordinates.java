package dev.omshutter.solver;

/** Immutable local horizontal coordinates derived from an ICRS pointing. */
public final class HorizontalCoordinates {
    private final double altitudeDeg;
    private final double azimuthDeg;

    private HorizontalCoordinates(double altitudeDeg, double azimuthDeg) {
        this.altitudeDeg = altitudeDeg;
        this.azimuthDeg = azimuthDeg;
    }

    /**
     * Converts an ICRS right ascension and declination to the local mean horizon of date.
     * Longitude is east-positive and azimuth is normalized to [0, 360), clockwise from north.
     */
    public static HorizontalCoordinates fromIcrs(
            double rightAscensionDeg,
            double declinationDeg,
            double latitudeDeg,
            double longitudeEastDeg,
            long utcEpochSecond,
            int utcNano) {
        requireFinite(rightAscensionDeg, "right ascension");
        requireFinite(declinationDeg, "declination");
        requireFinite(latitudeDeg, "latitude");
        requireFinite(longitudeEastDeg, "longitude");
        if (declinationDeg < -90.0 || declinationDeg > 90.0) {
            throw new IllegalArgumentException("declination must be between -90 and 90 degrees");
        }
        if (latitudeDeg < -90.0 || latitudeDeg > 90.0) {
            throw new IllegalArgumentException("latitude must be between -90 and 90 degrees");
        }

        double ra = Math.toRadians(normalizeUnsignedDegrees(rightAscensionDeg));
        double dec = Math.toRadians(declinationDeg);
        double cosDec = Math.cos(dec);
        double[] icrs = {
            cosDec * Math.cos(ra),
            cosDec * Math.sin(ra),
            Math.sin(dec)
        };
        double[][] precession = GravityRollEstimator.icrsToMeanEquatorOfDateMatrix(
                utcEpochSecond, utcNano);
        double[] target = multiply(precession, icrs);

        double latitude = Math.toRadians(latitudeDeg);
        double localSidereal = GravityRollEstimator.greenwichMeanSiderealTimeRadians(
                utcEpochSecond, utcNano) + Math.toRadians(longitudeEastDeg);
        double sinLatitude = Math.sin(latitude);
        double cosLatitude = Math.cos(latitude);
        double sinSidereal = Math.sin(localSidereal);
        double cosSidereal = Math.cos(localSidereal);

        double east = -sinSidereal * target[0] + cosSidereal * target[1];
        double north = -sinLatitude * cosSidereal * target[0]
                - sinLatitude * sinSidereal * target[1]
                + cosLatitude * target[2];
        double up = cosLatitude * cosSidereal * target[0]
                + cosLatitude * sinSidereal * target[1]
                + sinLatitude * target[2];

        double horizontal = Math.hypot(east, north);
        double altitude = Math.toDegrees(Math.atan2(up, horizontal));
        double azimuth = horizontal <= 1.0e-12
                ? Double.NaN
                : normalizeUnsignedDegrees(Math.toDegrees(Math.atan2(east, north)));
        return new HorizontalCoordinates(altitude, azimuth);
    }

    public double getAltitudeDeg() {
        return altitudeDeg;
    }

    public double getAzimuthDeg() {
        return azimuthDeg;
    }

    private static double[] multiply(double[][] matrix, double[] vector) {
        return new double[] {
            matrix[0][0] * vector[0] + matrix[0][1] * vector[1]
                    + matrix[0][2] * vector[2],
            matrix[1][0] * vector[0] + matrix[1][1] * vector[1]
                    + matrix[1][2] * vector[2],
            matrix[2][0] * vector[0] + matrix[2][1] * vector[1]
                    + matrix[2][2] * vector[2]
        };
    }

    private static double normalizeUnsignedDegrees(double value) {
        double normalized = value % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }
}
