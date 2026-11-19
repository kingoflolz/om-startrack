package dev.omshutter.ble;

import dev.omshutter.solver.CameraGravityWindow;
import dev.omshutter.solver.CorrectionAttitude;
import dev.omshutter.solver.GravityRollEstimator;
import dev.omshutter.solver.ManualEquatorialPointing;
import dev.omshutter.solver.ObserverLocationFix;

/** Immutable, ephemeral inputs for one explicitly requested manual gravity derivation. */
final class ManualGravityContext {
    static final class Derivation {
        private final CorrectionAttitude attitude;
        private final String failure;

        private Derivation(CorrectionAttitude attitude, String failure) {
            this.attitude = attitude;
            this.failure = failure;
        }

        static Derivation success(CorrectionAttitude attitude) {
            return new Derivation(attitude, "");
        }

        static Derivation failure(String reason) {
            return new Derivation(null, reason == null ? "" : reason);
        }

        boolean isValid() { return attitude != null; }
        CorrectionAttitude getAttitude() { return attitude; }
        String getFailure() { return failure; }
    }

    static final class UtcInstant {
        private final long epochSecond;
        private final int nano;

        UtcInstant(long epochSecond, int nano) {
            this.epochSecond = epochSecond;
            this.nano = nano;
        }

        long getEpochSecond() { return epochSecond; }
        int getNano() { return nano; }
    }

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final String rightAscension;
    private final String declination;
    private final long assertedElapsedRealtimeNanos;
    private final ObserverLocationFix location;
    private final long utcEpochSecond;
    private final int utcNano;
    private final long utcAnchorElapsedRealtimeNanos;

    private ManualGravityContext(
            String rightAscension,
            String declination,
            long assertedElapsedRealtimeNanos,
            ObserverLocationFix location,
            long utcEpochSecond,
            int utcNano,
            long utcAnchorElapsedRealtimeNanos) {
        if (assertedElapsedRealtimeNanos < 0L) {
            throw new IllegalArgumentException("pointing timestamp is negative");
        }
        if (utcNano < 0 || utcNano >= NANOS_PER_SECOND) {
            throw new IllegalArgumentException("UTC nanosecond is outside 0..999999999");
        }
        if (utcAnchorElapsedRealtimeNanos < 0L) {
            throw new IllegalArgumentException("UTC anchor timestamp is negative");
        }
        this.rightAscension = rightAscension == null ? "" : rightAscension.trim();
        this.declination = declination == null ? "" : declination.trim();
        this.assertedElapsedRealtimeNanos = assertedElapsedRealtimeNanos;
        this.location = location;
        this.utcEpochSecond = utcEpochSecond;
        this.utcNano = utcNano;
        this.utcAnchorElapsedRealtimeNanos = utcAnchorElapsedRealtimeNanos;
    }

    static ManualGravityContext capture(
            String rightAscension,
            String declination,
            long assertedElapsedRealtimeNanos,
            ObserverLocationFix location,
            long utcEpochSecond,
            int utcNano,
            long utcAnchorElapsedRealtimeNanos) {
        return new ManualGravityContext(
                rightAscension,
                declination,
                assertedElapsedRealtimeNanos,
                location,
                utcEpochSecond,
                utcNano,
                utcAnchorElapsedRealtimeNanos);
    }

    String getRightAscension() { return rightAscension; }
    String getDeclination() { return declination; }

    Derivation derive(
            CameraGravityWindow.Estimate gravity,
            long nowElapsedRealtimeNanos) {
        final ManualEquatorialPointing pointing;
        try {
            pointing = ManualEquatorialPointing.parse(
                    rightAscension, declination, assertedElapsedRealtimeNanos);
        } catch (IllegalArgumentException error) {
            return Derivation.failure(safeMessage(error));
        }

        final UtcInstant utc;
        try {
            utc = utcAt(nowElapsedRealtimeNanos);
        } catch (IllegalArgumentException | ArithmeticException error) {
            return Derivation.failure("Time unavailable");
        }
        if (utc.epochSecond < GravityRollEstimator.MIN_UTC_EPOCH_SECOND
                || utc.epochSecond > GravityRollEstimator.MAX_UTC_EPOCH_SECOND) {
            return Derivation.failure("Time unavailable");
        }
        final GravityRollEstimator.Result roll;
        try {
            roll = GravityRollEstimator.derive(
                    pointing,
                    location,
                    gravity,
                    utc.epochSecond,
                    utc.nano,
                    nowElapsedRealtimeNanos);
        } catch (IllegalArgumentException error) {
            return Derivation.failure("Time unavailable");
        }
        if (!roll.isValid()) return Derivation.failure(roll.getReason());
        return Derivation.success(CorrectionAttitude.of(
                pointing.getRightAscensionDeg(),
                pointing.getDeclinationDeg(),
                roll.getImageUpPositionAngleDeg()));
    }

    UtcInstant utcAt(long nowElapsedRealtimeNanos) {
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

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
