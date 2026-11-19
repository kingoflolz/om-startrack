package dev.omshutter;

import dev.omshutter.solver.ObserverLocationFix;

import java.time.DateTimeException;
import java.time.Instant;

/** Pure qualification and timestamp rules for one foreground observer fix. */
final class ForegroundLocationPolicy {
    static final float MAX_ACCURACY_METERS =
            (float) ObserverLocationFix.MAX_ACCURACY_METERS;
    static final long MAX_AGE_NANOS = ObserverLocationFix.MAX_AGE_NANOS;

    private ForegroundLocationPolicy() {}

    static boolean isQualified(
            double latitudeDeg,
            double longitudeDeg,
            boolean hasAccuracy,
            float accuracyMeters,
            long fixElapsedRealtimeNanos,
            long nowElapsedRealtimeNanos,
            boolean mockLocation) {
        if (mockLocation) return false;
        if (!Double.isFinite(latitudeDeg) || latitudeDeg < -90.0 || latitudeDeg > 90.0) {
            return false;
        }
        if (!Double.isFinite(longitudeDeg)
                || longitudeDeg < -180.0 || longitudeDeg > 180.0) {
            return false;
        }
        if (!hasAccuracy || !Float.isFinite(accuracyMeters)
                || accuracyMeters < 0f || accuracyMeters > MAX_ACCURACY_METERS) {
            return false;
        }
        if (fixElapsedRealtimeNanos <= 0L
                || nowElapsedRealtimeNanos < fixElapsedRealtimeNanos) {
            return false;
        }
        return nowElapsedRealtimeNanos - fixElapsedRealtimeNanos <= MAX_AGE_NANOS;
    }

    static Instant advanceLocationUtc(
            long locationEpochMillis,
            long fixElapsedRealtimeNanos,
            long nowElapsedRealtimeNanos) {
        if (locationEpochMillis <= 0L || fixElapsedRealtimeNanos <= 0L
                || nowElapsedRealtimeNanos < fixElapsedRealtimeNanos) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(locationEpochMillis)
                    .plusNanos(nowElapsedRealtimeNanos - fixElapsedRealtimeNanos);
        } catch (DateTimeException | ArithmeticException error) {
            return null;
        }
    }
}
