package dev.omshutter;

import dev.omshutter.solver.SolveResult;

import java.util.Locale;

/** Pure formatting helpers for the compact plate-solution presentation. */
final class PlateSolveFormatting {
    private static final long RA_TENTHS_PER_DAY = 24L * 60L * 60L * 10L;
    private static final long DEC_TENTHS_AT_POLE = 90L * 60L * 60L * 10L;

    private PlateSolveFormatting() {}

    /** Relative age on Android's monotonic clock, unaffected by wall-clock corrections. */
    static String relativeAge(long timestampNanos, long nowNanos, Locale locale) {
        if (timestampNanos < 0L || nowNanos < timestampNanos) return "—";
        long seconds = (nowNanos - timestampNanos) / 1_000_000_000L;
        if (seconds < 1L) return "just now";
        if (seconds < 60L) return String.format(locale, "%d s ago", seconds);
        if (seconds < 3_600L) return String.format(locale, "%d min ago", seconds / 60L);
        if (seconds < 86_400L) return String.format(locale, "%d h ago", seconds / 3_600L);
        return String.format(locale, "%d d ago", seconds / 86_400L);
    }

    static String rightAscension(double degrees, Locale locale) {
        if (!Double.isFinite(degrees)) return "—";
        double normalized = degrees % 360d;
        if (normalized < 0d) normalized += 360d;
        long tenths = Math.round(normalized / 360d * RA_TENTHS_PER_DAY)
                % RA_TENTHS_PER_DAY;
        long hours = tenths / 36_000L;
        long minutes = (tenths / 600L) % 60L;
        long seconds = (tenths / 10L) % 60L;
        long fraction = tenths % 10L;
        return String.format(
                locale, "%02dh %02dm %02d.%01ds", hours, minutes, seconds, fraction);
    }

    static String declination(double degrees, Locale locale) {
        if (!Double.isFinite(degrees) || degrees < -90d || degrees > 90d) return "—";
        long tenths = Math.min(
                DEC_TENTHS_AT_POLE,
                Math.round(Math.abs(degrees) * 60d * 60d * 10d));
        long wholeDegrees = tenths / 36_000L;
        long minutes = (tenths / 600L) % 60L;
        long seconds = (tenths / 10L) % 60L;
        long fraction = tenths % 10L;
        String sign = Double.doubleToRawLongBits(degrees) < 0L ? "−" : "+";
        return String.format(
                locale,
                "%s%02d° %02d′ %02d.%01d″",
                sign,
                wholeDegrees,
                minutes,
                seconds,
                fraction);
    }

    static String degrees(double value, Locale locale) {
        return Double.isFinite(value) ? String.format(locale, "%.2f°", value) : "—";
    }

    static String attitudeDegrees(double value, Locale locale) {
        return Double.isFinite(value) ? String.format(locale, "%.3f°", value) : "—";
    }

    static String arcseconds(double value, Locale locale) {
        return Double.isFinite(value) ? String.format(locale, "%.1f″", value) : "—";
    }

    /** Both focal lengths belong to this solve; absent calibration clears the comparison. */
    static String focalComparison(SolveResult result, String pattern, Locale locale) {
        if (result == null || !result.hasFocalCalibration()) return "";
        double supplied = result.getSuppliedFocalLengthMm();
        double solved = result.getSolvedFocalLengthMm();
        double difference = solved - supplied;
        double percent = 100.0 * difference / supplied;
        return String.format(locale, pattern, solved, supplied,
                withoutRoundedNegativeZero(difference), withoutRoundedNegativeZero(percent));
    }

    private static double withoutRoundedNegativeZero(double value) {
        return Math.abs(value) < 0.005 ? 0.0 : value;
    }
}
