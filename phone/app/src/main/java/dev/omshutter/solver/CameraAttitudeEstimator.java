package dev.omshutter.solver;

/**
 * Capture-anchored camera pointing in a fixed local east/north/up frame.
 *
 * <p>Hamilton scalar-first quaternions map camera body coordinates (right, down, forward)
 * into the gyro integrator's arbitrary reference. For a solved capture, the retained alignment
 * is {@code Hcapture * transpose(R(Qcapture))}; current pointing is that alignment applied to
 * {@code R(Qcurrent)}. Clock time is used only to establish the capture's horizon frame. It does
 * not move the pointing of a stationary camera after anchoring.
 *
 * <p>This estimates relative camera motion from the selected gyro stream, not a calibrated
 * inertial attitude or a Kalman filter. The caller must associate the capture latch with the
 * correct image and provide its qualified UTC. A capture-preparation latch approximates image
 * attitude only when movement before/during exposure is negligible. No sample history is kept.
 */
public final class CameraAttitudeEstimator {
    public static final long MAX_SAMPLE_AGE_NANOS = 500_000_000L;

    private static final long U32_MASK = 0xffff_ffffL;
    private static final long U32_HALF_RANGE = 0x8000_0000L;
    private static final double Q30_SCALE = 1_073_741_824.0;
    // Avoid assigning a heading to roundoff-sized horizontal components at the zenith/nadir.
    private static final double MIN_HORIZONTAL_NORM = 1.0e-8;

    public enum State { UNAVAILABLE, UNANCHORED, STALE, AVAILABLE }

    /** Immutable capture pose tied to this estimator's uninterrupted source interval. */
    public static final class Capture {
        private final CameraAttitudeEstimator owner;
        private final long continuity;
        private final long sequence;
        private final long tick;
        private final double[] quaternion;

        private Capture(CameraAttitudeEstimator owner, long continuity,
                long sequence, long tick, double[] quaternion) {
            this.owner = owner;
            this.continuity = continuity;
            this.sequence = sequence;
            this.tick = tick;
            this.quaternion = quaternion;
        }

        public long getCaptureSequence() { return sequence; }
        public long getCaptureTick() { return tick; }
    }

    /** Immutable display result. Azimuth and roll are unavailable (NaN) at the zenith and nadir. */
    public static final class Estimate {
        private final State state;
        private final double altitudeDeg;
        private final double azimuthDeg;
        private final double rollDeg;
        private final long captureSequence;
        private final long tick;
        private final long receivedElapsedRealtimeNanos;

        private Estimate(State state, double altitudeDeg, double azimuthDeg, double rollDeg,
                long captureSequence, long tick, long receivedElapsedRealtimeNanos) {
            this.state = state;
            this.altitudeDeg = altitudeDeg;
            this.azimuthDeg = azimuthDeg;
            this.rollDeg = rollDeg;
            this.captureSequence = captureSequence;
            this.tick = tick;
            this.receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos;
        }

        public State getState() { return state; }
        public boolean isAvailable() { return state == State.AVAILABLE; }
        public double getAltitudeDeg() { return altitudeDeg; }
        public double getAzimuthDeg() { return azimuthDeg; }
        /** Clockwise from behind the camera, in [-180, 180) degrees. */
        public double getRollDeg() { return rollDeg; }
        public long getCaptureSequence() { return captureSequence; }
        public long getTick() { return tick; }
        public long getReceivedElapsedRealtimeNanos() { return receivedElapsedRealtimeNanos; }
    }

    private long continuity;
    private long tick;
    private long firstTick;
    private long receivedElapsedRealtimeNanos = -1L;
    private double[] currentQuaternion;
    private boolean sourceAvailable;
    private double[] referenceToEnu;
    private long anchoredCaptureSequence = -1L;

    public synchronized boolean accept(long tick, int[] q30, long receiptNanos) {
        try {
            return accept(tick, fromQ30(q30), receiptNanos);
        } catch (IllegalArgumentException error) {
            pause();
            throw error;
        }
    }

    /**
     * Admits one already source-qualified pose. Duplicate source ticks do not refresh freshness.
     * Tick regressions retire prior capture/solve associations. Notification
     * gaps are harmless to the cumulative rotation; the producer must flag gyro sampling gaps.
     */
    public synchronized boolean accept(long tick, double[] quaternion,
            long receiptNanos) {
        double[] normalized;
        try {
            requireU32(tick, "tick");
            if (receiptNanos < 0L) throw new IllegalArgumentException("negative receipt time");
            normalized = normalize(quaternion);
        } catch (IllegalArgumentException error) {
            pause();
            throw error;
        }
        if (receivedElapsedRealtimeNanos >= 0L && receiptNanos < receivedElapsedRealtimeNanos) {
            return false;
        }
        long advance = (tick - this.tick) & U32_MASK;
        boolean newInterval = currentQuaternion == null || advance >= U32_HALF_RANGE;
        if (!newInterval && advance == 0L) {
            if (Math.abs(dot(currentQuaternion, normalized)) < 1.0 - 1.0e-12) {
                invalidate();
            }
            return false;
        }
        if (newInterval) {
            invalidate();
            firstTick = tick;
        }
        this.tick = tick;
        currentQuaternion = normalized;
        sourceAvailable = true;
        receivedElapsedRealtimeNanos = receiptNanos;
        return true;
    }

    public synchronized Capture capture(long captureSequence, long captureTick,
            int[] captureQ30) {
        return capture(captureSequence, captureTick, fromQ30(captureQ30));
    }

    /**
     * Retains an image's latch for a later solve result. Returns null when the latch belongs to
     * before this connection's source interval or is ahead of the latest pose.
     */
    public synchronized Capture capture(long captureSequence, long captureTick,
            double[] captureQuaternion) {
        requireU32(captureSequence, "capture sequence");
        requireU32(captureTick, "capture tick");
        double[] normalized = normalize(captureQuaternion);
        if (currentQuaternion == null
                || ((captureTick - firstTick) & U32_MASK) >= U32_HALF_RANGE
                || ((tick - captureTick) & U32_MASK) >= U32_HALF_RANGE) {
            return null;
        }
        return new Capture(this, continuity, captureSequence, captureTick, normalized);
    }

    /** Establishes the local horizon once, at the caller-qualified capture UTC. */
    public synchronized boolean anchor(SolveResult solve, Capture capture,
            double latitudeDeg, double longitudeEastDeg, long captureUtcSecond, int captureUtcNano) {
        if (capture == null || capture.owner != this || capture.continuity != continuity
                || currentQuaternion == null) {
            return false;
        }
        if (solve == null) throw new IllegalArgumentException("plate solution is unavailable");
        double[] bodyToEnu = solvedBodyToEnu(solve, latitudeDeg, longitudeEastDeg,
                captureUtcSecond, captureUtcNano);
        referenceToEnu = multiply(bodyToEnu, transpose(rotation(capture.quaternion)));
        anchoredCaptureSequence = capture.sequence;
        return true;
    }

    /**
     * Treats the current fresh pose as altitude/azimuth 0/0 with level roll for gyro testing.
     * Camera right/down/forward then maps to east/down/north. A later solve replaces this
     * reference normally. Returns false without changing the reference when live data is stale
     * or unavailable.
     */
    public synchronized boolean zeroForTesting(long nowNanos) {
        State state = estimate(nowNanos).getState();
        if (state != State.AVAILABLE && state != State.UNANCHORED) return false;
        double[] zeroBodyToEnu = {1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, -1.0, 0.0};
        referenceToEnu = multiply(zeroBodyToEnu, transpose(rotation(currentQuaternion)));
        anchoredCaptureSequence = -1L;
        return true;
    }

    /** A new solve intent may remove the old pointing without breaking gyro continuity. */
    public synchronized void clearAnchor() {
        referenceToEnu = null;
        anchoredCaptureSequence = -1L;
    }

    /** Hide unavailable live data while retaining the fixed reference and last solve. */
    public synchronized void pause() { sourceAvailable = false; }

    /** Reconnection or a regressing source counter retires the previous reference. */
    public synchronized void invalidate() {
        continuity++;
        currentQuaternion = null;
        sourceAvailable = false;
        receivedElapsedRealtimeNanos = -1L;
        clearAnchor();
    }

    public synchronized void clear() { invalidate(); }

    public synchronized Estimate estimate(long nowElapsedRealtimeNanos) {
        if (nowElapsedRealtimeNanos < 0L) throw new IllegalArgumentException("negative estimate time");
        State state;
        if (currentQuaternion == null || !sourceAvailable) state = State.UNAVAILABLE;
        else if (nowElapsedRealtimeNanos < receivedElapsedRealtimeNanos
                || nowElapsedRealtimeNanos - receivedElapsedRealtimeNanos > MAX_SAMPLE_AGE_NANOS) {
            state = State.STALE;
        } else if (referenceToEnu == null) state = State.UNANCHORED;
        else state = State.AVAILABLE;
        if (state != State.AVAILABLE) {
            return new Estimate(state, Double.NaN, Double.NaN, Double.NaN, anchoredCaptureSequence,
                    tick, receivedElapsedRealtimeNanos);
        }
        double[] currentRotation = rotation(currentQuaternion);
        double[] forward = rotate(referenceToEnu,
                new double[]{currentRotation[2], currentRotation[5], currentRotation[8]});
        double horizontal = Math.hypot(forward[0], forward[1]);
        double altitude = Math.toDegrees(Math.atan2(forward[2], horizontal));
        if (Math.abs(altitude) < 1.0e-12) altitude = 0.0;
        double azimuth = horizontal <= MIN_HORIZONTAL_NORM ? Double.NaN
                : unsignedDegrees(Math.toDegrees(Math.atan2(forward[0], forward[1])));
        double rightUp = referenceToEnu[6] * currentRotation[0]
                + referenceToEnu[7] * currentRotation[3] + referenceToEnu[8] * currentRotation[6];
        double downUp = referenceToEnu[6] * currentRotation[1]
                + referenceToEnu[7] * currentRotation[4] + referenceToEnu[8] * currentRotation[7];
        double roll = horizontal <= MIN_HORIZONTAL_NORM ? Double.NaN
                : Math.toDegrees(Math.atan2(-rightUp, -downUp));
        if (roll >= 180.0) roll -= 360.0;
        if (Math.abs(roll) < 1.0e-12) roll = 0.0;
        return new Estimate(State.AVAILABLE, altitude, azimuth, roll, anchoredCaptureSequence,
                tick, receivedElapsedRealtimeNanos);
    }

    private static double[] solvedBodyToEnu(SolveResult solve, double latitudeDeg,
            double longitudeEastDeg, long utcSecond, int utcNano) {
        if (!Double.isFinite(latitudeDeg) || latitudeDeg < -90.0 || latitudeDeg > 90.0
                || !Double.isFinite(longitudeEastDeg)
                || solve.getDeclinationDeg() < -90.0 || solve.getDeclinationDeg() > 90.0) {
            throw new IllegalArgumentException("invalid capture pointing or observer location");
        }
        double ra = Math.toRadians(solve.getRightAscensionDeg());
        double dec = Math.toRadians(solve.getDeclinationDeg());
        double roll = Math.toRadians(solve.getRollDeg());
        double[] east = {-Math.sin(ra), Math.cos(ra), 0.0};
        double[] north = {-Math.sin(dec) * Math.cos(ra),
                -Math.sin(dec) * Math.sin(ra), Math.cos(dec)};
        double[] forward = {Math.cos(dec) * Math.cos(ra),
                Math.cos(dec) * Math.sin(ra), Math.sin(dec)};
        double[] bodyToIcrs = new double[9];
        for (int row = 0; row < 3; row++) {
            // Image up has position angle roll east of celestial north; body +Y is image down.
            bodyToIcrs[row * 3] = -east[row] * Math.cos(roll) + north[row] * Math.sin(roll);
            bodyToIcrs[row * 3 + 1] = -east[row] * Math.sin(roll) - north[row] * Math.cos(roll);
            bodyToIcrs[row * 3 + 2] = forward[row];
        }
        double[][] precessionMatrix = GravityRollEstimator.icrsToMeanEquatorOfDateMatrix(
                utcSecond, utcNano);
        double[] precession = new double[9];
        for (int row = 0; row < 3; row++) {
            System.arraycopy(precessionMatrix[row], 0, precession, row * 3, 3);
        }
        double latitude = Math.toRadians(latitudeDeg);
        double sidereal = GravityRollEstimator.greenwichMeanSiderealTimeRadians(utcSecond, utcNano)
                + Math.toRadians(unsignedDegrees(longitudeEastDeg));
        double sinLat = Math.sin(latitude), cosLat = Math.cos(latitude);
        double sinSidereal = Math.sin(sidereal), cosSidereal = Math.cos(sidereal);
        double[] ofDateToEnu = {
                -sinSidereal, cosSidereal, 0.0,
                -sinLat * cosSidereal, -sinLat * sinSidereal, cosLat,
                cosLat * cosSidereal, cosLat * sinSidereal, sinLat
        };
        return multiply(ofDateToEnu, multiply(precession, bodyToIcrs));
    }

    private static double[] fromQ30(int[] q30) {
        if (q30 == null || q30.length != 4) throw new IllegalArgumentException("quaternion needs four lanes");
        return new double[]{q30[0] / Q30_SCALE, q30[1] / Q30_SCALE,
                q30[2] / Q30_SCALE, q30[3] / Q30_SCALE};
    }

    private static double[] normalize(double[] quaternion) {
        if (quaternion == null || quaternion.length != 4) {
            throw new IllegalArgumentException("quaternion needs four lanes");
        }
        double normSquared = dot(quaternion, quaternion);
        if (!Double.isFinite(normSquared) || normSquared < 0.81 || normSquared > 1.21) {
            throw new IllegalArgumentException("quaternion is not near unit length");
        }
        double norm = Math.sqrt(normSquared);
        return new double[]{quaternion[0] / norm, quaternion[1] / norm,
                quaternion[2] / norm, quaternion[3] / norm};
    }

    private static double dot(double[] a, double[] b) {
        double result = 0.0;
        for (int i = 0; i < a.length; i++) result += a[i] * b[i];
        return result;
    }

    private static double[] rotation(double[] q) {
        double w = q[0], x = q[1], y = q[2], z = q[3];
        return new double[]{
                1 - 2 * (y*y + z*z), 2 * (x*y - w*z), 2 * (x*z + w*y),
                2 * (x*y + w*z), 1 - 2 * (x*x + z*z), 2 * (y*z - w*x),
                2 * (x*z - w*y), 2 * (y*z + w*x), 1 - 2 * (x*x + y*y)
        };
    }

    private static double[] transpose(double[] a) {
        return new double[]{a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]};
    }

    private static double[] multiply(double[] a, double[] b) {
        double[] product = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                for (int k = 0; k < 3; k++) {
                    product[row * 3 + column] += a[row * 3 + k] * b[k * 3 + column];
                }
            }
        }
        return product;
    }

    private static double[] rotate(double[] matrix, double[] vector) {
        return new double[]{
                matrix[0] * vector[0] + matrix[1] * vector[1] + matrix[2] * vector[2],
                matrix[3] * vector[0] + matrix[4] * vector[1] + matrix[5] * vector[2],
                matrix[6] * vector[0] + matrix[7] * vector[1] + matrix[8] * vector[2]
        };
    }

    private static double unsignedDegrees(double value) {
        double result = value % 360.0;
        if (result < 0.0) result += 360.0;
        // An exactly north-pointing pose can acquire a tiny negative angle from matrix roundoff.
        return result >= 360.0 - 1.0e-12 ? 0.0 : result;
    }

    private static void requireU32(long value, String name) {
        if (value < 0L || value > U32_MASK) throw new IllegalArgumentException(name + " is not u32");
    }
}
