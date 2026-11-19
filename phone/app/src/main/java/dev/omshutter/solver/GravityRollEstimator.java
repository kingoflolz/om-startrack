package dev.omshutter.solver;

/** Derives image-up position angle from current pointing, location, time, and gravity. */
public final class GravityRollEstimator {
    public static final double MIN_PROJECTION = 0.20;
    public static final double MAX_ALTITUDE_DISAGREEMENT_DEG = 3.0;
    public static final long MIN_UTC_EPOCH_SECOND = 0L;
    public static final long MAX_UTC_EPOCH_SECOND = 4_102_444_800L;

    private static final long J2000_NOON_UNIX_SECONDS = 946_728_000L;
    private static final double SECONDS_PER_DAY = 86_400.0;
    private static final double DAYS_PER_JULIAN_CENTURY = 36_525.0;
    private static final double ARCSECONDS_TO_RADIANS =
            Math.PI / (180.0 * 3_600.0);
    private static final double TWO_PI = 2.0 * Math.PI;

    public enum Status {
        VALID,
        MISSING_POINTING,
        MISSING_LOCATION,
        MISSING_GRAVITY,
        UNSTABLE,
        MISMATCH,
        SINGULAR
    }

    /** Immutable successful derivation or one compact, user-presentable failure. */
    public static final class Result {
        private final Status status;
        private final String reason;
        private final double imageUpPositionAngleDeg;
        private final double altitudeDeg;
        private final double projection;

        private Result(
                Status status,
                String reason,
                double imageUpPositionAngleDeg,
                double altitudeDeg,
                double projection) {
            this.status = status;
            this.reason = reason;
            this.imageUpPositionAngleDeg = imageUpPositionAngleDeg;
            this.altitudeDeg = altitudeDeg;
            this.projection = projection;
        }

        private static Result failure(Status status, String reason) {
            return new Result(status, reason, Double.NaN, Double.NaN, Double.NaN);
        }

        private static Result valid(double positionAngleDeg, double altitudeDeg, double projection) {
            return new Result(Status.VALID, "", positionAngleDeg, altitudeDeg, projection);
        }

        public Status getStatus() {
            return status;
        }

        public boolean isValid() {
            return status == Status.VALID;
        }

        public String getReason() {
            return reason;
        }

        public double getImageUpPositionAngleDeg() {
            requireValid();
            return imageUpPositionAngleDeg;
        }

        public double getAltitudeDeg() {
            requireValid();
            return altitudeDeg;
        }

        public double getProjection() {
            requireValid();
            return projection;
        }

        private void requireValid() {
            if (!isValid()) {
                throw new IllegalStateException("gravity-derived roll is unavailable");
            }
        }
    }

    private GravityRollEstimator() {}

    /**
     * Derives roll at one injected UTC and monotonic instant.
     *
     * <p>Longitude is east-positive. Gravity lanes are physical down in the shared camera frame:
     * +X image-right, +Y image-down, and +Z forward through the lens.
     */
    public static Result derive(
            ManualEquatorialPointing pointing,
            ObserverLocationFix location,
            CameraGravityWindow.Estimate gravity,
            long utcEpochSecond,
            int utcNano,
            long elapsedRealtimeNanos) {
        validateTime(utcEpochSecond, utcNano, elapsedRealtimeNanos);
        if (pointing == null
                || pointing.getAssertedElapsedRealtimeNanos() > elapsedRealtimeNanos
                || !pointing.isCurrentAt(elapsedRealtimeNanos)) {
            return Result.failure(Status.MISSING_POINTING, "RA/Dec required");
        }
        if (location == null
                || location.getFixElapsedRealtimeNanos() > elapsedRealtimeNanos
                || !location.isQualifiedAt(elapsedRealtimeNanos)) {
            return Result.failure(Status.MISSING_LOCATION, "Location unavailable");
        }
        if (gravity == null
                || gravity.getState() == CameraGravityWindow.State.UNAVAILABLE
                || gravity.getNewestElapsedRealtimeNanos() < 0L
                || gravity.getNewestElapsedRealtimeNanos() > elapsedRealtimeNanos
                || elapsedRealtimeNanos - gravity.getNewestElapsedRealtimeNanos()
                    > CameraGravityWindow.MAX_SAMPLE_AGE_NANOS) {
            return Result.failure(Status.MISSING_GRAVITY, "Camera tilt unavailable");
        }
        if (gravity.getState() == CameraGravityWindow.State.UNSTABLE) {
            return Result.failure(Status.UNSTABLE, "Hold camera still");
        }

        double[] celestialZenith = celestialZenithComponents(
                Math.toRadians(pointing.getRightAscensionDeg()),
                Math.toRadians(pointing.getDeclinationDeg()),
                Math.toRadians(location.getLatitudeDeg()),
                Math.toRadians(location.getLongitudeEastDeg()),
                utcEpochSecond,
                utcNano);
        return deriveQualified(
                celestialZenith[0],
                celestialZenith[1],
                celestialZenith[2],
                gravity);
    }

    /** IAU Earth Rotation Angle, treating injected UTC as UT1 for this bounded application. */
    public static double earthRotationAngleRadians(long utcEpochSecond, int utcNano) {
        if (utcEpochSecond < MIN_UTC_EPOCH_SECOND
                || utcEpochSecond > MAX_UTC_EPOCH_SECOND) {
            throw new IllegalArgumentException("UTC instant is outside 1970..2100");
        }
        if (utcNano < 0 || utcNano >= 1_000_000_000) {
            throw new IllegalArgumentException("UTC nanosecond is outside 0..999999999");
        }
        double daysSinceJ2000 = daysSinceJ2000(utcEpochSecond, utcNano);
        // Split off the integral-day contribution (an exact whole turn) before multiplying.
        // This preserves fractional-day precision across the supported 1970..2100 range.
        double turns = daysSinceJ2000 % 1.0
                + 0.7790572732640
                + 0.00273781191135448 * daysSinceJ2000;
        return TWO_PI * (turns - Math.floor(turns));
    }

    /**
     * IAU 2006 Greenwich mean sidereal time for the mean equinox of date.
     *
     * <p>The injected UTC is used as both UT1 and TT. With {@code projection >= 0.20}, treating
     * UTC as UT1 (at most 0.9 s away), and omitting nutation and polar motion, bounds the resulting
     * position-angle model error to about 0.05 degrees. Accelerometer and location errors dominate.
     */
    public static double greenwichMeanSiderealTimeRadians(
            long utcEpochSecond, int utcNano) {
        double era = earthRotationAngleRadians(utcEpochSecond, utcNano);
        double centuries = daysSinceJ2000(utcEpochSecond, utcNano)
                / DAYS_PER_JULIAN_CENTURY;
        double precessionArcseconds = 0.014506
                + centuries * (4612.156534
                + centuries * (1.3915817
                + centuries * (-0.00000044
                + centuries * (-0.000029956
                + centuries * -0.0000000368))));
        return normalizeUnsignedRadians(
                era + Math.toRadians(precessionArcseconds / 3_600.0));
    }

    /**
     * IAU 2006 P03 bias-precession matrix from ICRS/GCRS axes to the mean equator and
     * equinox of the injected date.
     *
     * <p>This is the Fukushima-Williams four-angle construction. It deliberately omits
     * nutation: GMST and the local zenith are both expressed in the same mean-of-date frame.
     * UTC is used as TT; the sub-milliarcsecond timing effect is negligible here.
     */
    static double[][] icrsToMeanEquatorOfDateMatrix(
            long utcEpochSecond, int utcNano) {
        validateUtc(utcEpochSecond, utcNano);
        double centuries = daysSinceJ2000(utcEpochSecond, utcNano)
                / DAYS_PER_JULIAN_CENTURY;

        double gammaBar = arcsecondsToRadians(
                -0.052928
                        + centuries * (10.556378
                        + centuries * (0.4932044
                        + centuries * (-0.00031238
                        + centuries * (-0.000002788
                        + centuries * 0.0000000260)))));
        double phiBar = arcsecondsToRadians(
                84_381.412819
                        + centuries * (-46.811016
                        + centuries * (0.0511268
                        + centuries * (0.00053289
                        + centuries * (-0.000000440
                        + centuries * -0.0000000176)))));
        double psiBar = arcsecondsToRadians(
                -0.041775
                        + centuries * (5_038.481484
                        + centuries * (1.5584175
                        + centuries * (-0.00018522
                        + centuries * (-0.000026452
                        + centuries * -0.0000000148)))));
        double epsilon = arcsecondsToRadians(
                84_381.406
                        + centuries * (-46.836769
                        + centuries * (-0.0001831
                        + centuries * (0.00200340
                        + centuries * (-0.000000576
                        + centuries * -0.0000000434)))));

        // Coordinate rotations: R1(-epsilon) R3(-psi) R1(phi) R3(gamma).
        double[][] matrix = identityMatrix();
        matrix = multiply(rotationZ(gammaBar), matrix);
        matrix = multiply(rotationX(phiBar), matrix);
        matrix = multiply(rotationZ(-psiBar), matrix);
        return multiply(rotationX(-epsilon), matrix);
    }

    /** Returns local zenith components along ICRS target east, north, and forward. */
    static double[] celestialZenithComponents(
            double rightAscension,
            double declination,
            double latitude,
            double longitudeEast,
            long utcEpochSecond,
            int utcNano) {
        double sinRa = Math.sin(rightAscension);
        double cosRa = Math.cos(rightAscension);
        double sinDec = Math.sin(declination);
        double cosDec = Math.cos(declination);

        // The tangent basis is pinned to the solver/manual ICRS/J2000 RA/Dec convention.
        double[] targetForward = {cosDec * cosRa, cosDec * sinRa, sinDec};
        double[] targetEast = {-sinRa, cosRa, 0.0};
        double[] targetNorth = {-sinDec * cosRa, -sinDec * sinRa, cosDec};

        double[][] biasPrecession = icrsToMeanEquatorOfDateMatrix(
                utcEpochSecond, utcNano);
        double[] forwardOfDate = rotate(biasPrecession, targetForward);
        double[] eastOfDate = rotate(biasPrecession, targetEast);
        double[] northOfDate = rotate(biasPrecession, targetNorth);

        double localSidereal = normalizeUnsignedRadians(
                greenwichMeanSiderealTimeRadians(utcEpochSecond, utcNano)
                        + longitudeEast);
        double cosLatitude = Math.cos(latitude);
        double[] zenithOfDate = {
            cosLatitude * Math.cos(localSidereal),
            cosLatitude * Math.sin(localSidereal),
            Math.sin(latitude)
        };
        return new double[] {
            dot(zenithOfDate, eastOfDate),
            dot(zenithOfDate, northOfDate),
            dot(zenithOfDate, forwardOfDate)
        };
    }

    static Result deriveQualified(
            double zenithEast,
            double zenithNorth,
            double zenithForward,
            CameraGravityWindow.Estimate gravity) {
        double gravityNorm = gravity.getMeanNormMillig();
        double downX = gravity.getDownXMillig() / gravityNorm;
        double downY = gravity.getDownYMillig() / gravityNorm;
        double downZ = gravity.getDownZMillig() / gravityNorm;
        double cameraProjection = Math.hypot(downX, downY);
        double skyProjection = Math.hypot(zenithEast, zenithNorth);
        double projection = Math.min(cameraProjection, skyProjection);
        if (projection < MIN_PROJECTION) {
            return Result.failure(Status.SINGULAR, "Too close to vertical");
        }

        double skyAltitude = Math.asin(clampUnit(zenithForward));
        double cameraAltitude = Math.asin(clampUnit(-downZ));
        if (Math.abs(skyAltitude - cameraAltitude)
                > Math.toRadians(MAX_ALTITUDE_DISAGREEMENT_DEG)) {
            return Result.failure(
                    Status.MISMATCH, "RA/Dec doesn't match camera tilt");
        }

        double zenithPositionAngle = Math.atan2(zenithEast, zenithNorth);
        double downImageAngle = Math.atan2(downX, downY);
        double imageUpPositionAngle = normalizeSignedRadians(
                zenithPositionAngle - downImageAngle);
        return Result.valid(
                Math.toDegrees(imageUpPositionAngle),
                Math.toDegrees(skyAltitude),
                projection);
    }

    private static void validateTime(
            long utcEpochSecond, int utcNano, long elapsedRealtimeNanos) {
        validateUtc(utcEpochSecond, utcNano);
        if (elapsedRealtimeNanos < 0L) {
            throw new IllegalArgumentException("elapsed realtime is negative");
        }
    }

    private static void validateUtc(long utcEpochSecond, int utcNano) {
        if (utcEpochSecond < MIN_UTC_EPOCH_SECOND
                || utcEpochSecond > MAX_UTC_EPOCH_SECOND) {
            throw new IllegalArgumentException("UTC instant is outside 1970..2100");
        }
        if (utcNano < 0 || utcNano >= 1_000_000_000) {
            throw new IllegalArgumentException("UTC nanosecond is outside 0..999999999");
        }
    }

    private static double daysSinceJ2000(long utcEpochSecond, int utcNano) {
        return ((double) utcEpochSecond
                - J2000_NOON_UNIX_SECONDS
                + utcNano * 1.0e-9) / SECONDS_PER_DAY;
    }

    private static double arcsecondsToRadians(double arcseconds) {
        return arcseconds * ARCSECONDS_TO_RADIANS;
    }

    private static double[][] identityMatrix() {
        return new double[][] {
            {1.0, 0.0, 0.0},
            {0.0, 1.0, 0.0},
            {0.0, 0.0, 1.0}
        };
    }

    // These rotate coordinate components, matching the IAU matrix convention.
    private static double[][] rotationX(double angle) {
        double sine = Math.sin(angle);
        double cosine = Math.cos(angle);
        return new double[][] {
            {1.0, 0.0, 0.0},
            {0.0, cosine, sine},
            {0.0, -sine, cosine}
        };
    }

    private static double[][] rotationZ(double angle) {
        double sine = Math.sin(angle);
        double cosine = Math.cos(angle);
        return new double[][] {
            {cosine, sine, 0.0},
            {-sine, cosine, 0.0},
            {0.0, 0.0, 1.0}
        };
    }

    private static double[][] multiply(double[][] left, double[][] right) {
        double[][] product = new double[3][3];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                product[row][column] = left[row][0] * right[0][column]
                        + left[row][1] * right[1][column]
                        + left[row][2] * right[2][column];
            }
        }
        return product;
    }

    private static double[] rotate(double[][] matrix, double[] vector) {
        return new double[] {
            dot(matrix[0], vector),
            dot(matrix[1], vector),
            dot(matrix[2], vector)
        };
    }

    private static double dot(double[] left, double[] right) {
        return left[0] * right[0] + left[1] * right[1] + left[2] * right[2];
    }

    private static double normalizeSignedRadians(double value) {
        double normalized = value % TWO_PI;
        if (normalized < -Math.PI) normalized += TWO_PI;
        if (normalized >= Math.PI) normalized -= TWO_PI;
        return normalized;
    }

    private static double normalizeUnsignedRadians(double value) {
        double normalized = value % TWO_PI;
        return normalized < 0.0 ? normalized + TWO_PI : normalized;
    }

    private static double clampUnit(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }
}
