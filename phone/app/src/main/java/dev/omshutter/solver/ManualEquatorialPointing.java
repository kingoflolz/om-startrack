package dev.omshutter.solver;

import java.util.Objects;

/** A manually asserted current optical-axis pointing, without an image-roll input. */
public final class ManualEquatorialPointing {
    /** A manual pointing must still describe the camera when correction is calculated. */
    public static final long MAX_AGE_NANOS = 30_000_000_000L;

    private final double rightAscensionDeg;
    private final double declinationDeg;
    private final long assertedElapsedRealtimeNanos;

    private ManualEquatorialPointing(
            double rightAscensionDeg,
            double declinationDeg,
            long assertedElapsedRealtimeNanos) {
        this.rightAscensionDeg = rightAscensionDeg;
        this.declinationDeg = declinationDeg;
        this.assertedElapsedRealtimeNanos = assertedElapsedRealtimeNanos;
    }

    public static ManualEquatorialPointing of(
            double rightAscensionDeg,
            double declinationDeg,
            long assertedElapsedRealtimeNanos) {
        requireFinite(rightAscensionDeg, "right ascension");
        requireFinite(declinationDeg, "declination");
        if (declinationDeg < -90.0 || declinationDeg > 90.0) {
            throw new IllegalArgumentException("declination must be between -90 and 90 degrees");
        }
        if (assertedElapsedRealtimeNanos < 0L) {
            throw new IllegalArgumentException("pointing timestamp is negative");
        }
        return new ManualEquatorialPointing(
                normalizeUnsignedDegrees(rightAscensionDeg),
                declinationDeg,
                assertedElapsedRealtimeNanos);
    }

    public static ManualEquatorialPointing parse(
            String rightAscension,
            String declination,
            long assertedElapsedRealtimeNanos) {
        String ra = requireText(rightAscension);
        String dec = requireText(declination);
        try {
            return of(
                    Double.parseDouble(ra),
                    Double.parseDouble(dec),
                    assertedElapsedRealtimeNanos);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("RA/Dec must be decimal numbers", error);
        }
    }

    public double getRightAscensionDeg() {
        return rightAscensionDeg;
    }

    public double getDeclinationDeg() {
        return declinationDeg;
    }

    public long getAssertedElapsedRealtimeNanos() {
        return assertedElapsedRealtimeNanos;
    }

    public boolean isCurrentAt(long elapsedRealtimeNanos) {
        if (elapsedRealtimeNanos < assertedElapsedRealtimeNanos) {
            throw new IllegalArgumentException("elapsed realtime precedes pointing timestamp");
        }
        return elapsedRealtimeNanos - assertedElapsedRealtimeNanos <= MAX_AGE_NANOS;
    }

    private static String requireText(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("RA/Dec required");
        return trimmed;
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    private static double normalizeUnsignedDegrees(double value) {
        double normalized = value % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ManualEquatorialPointing)) return false;
        ManualEquatorialPointing that = (ManualEquatorialPointing) other;
        return Double.compare(rightAscensionDeg, that.rightAscensionDeg) == 0
                && Double.compare(declinationDeg, that.declinationDeg) == 0
                && assertedElapsedRealtimeNanos == that.assertedElapsedRealtimeNanos;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                rightAscensionDeg, declinationDeg, assertedElapsedRealtimeNanos);
    }
}
