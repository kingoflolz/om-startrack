package dev.omshutter.protocol;

import java.math.BigDecimal;

/** Lossless display helpers for camera/detector measurements. */
public final class MeasurementFormat {
    private MeasurementFormat() {}

    /** Preserves every bit of the decoded IEEE binary32 focal-length value. */
    public static String focalLength(float focalLengthMm) {
        if (!Float.isFinite(focalLengthMm) || !(focalLengthMm > 0.0f)) {
            throw new IllegalArgumentException("focal length must be finite and positive");
        }
        return Double.toString((double) focalLengthMm);
    }

    /** Q8 values are binary fractions, so this decimal rendering is exact. */
    public static String unsignedQ8(long value) {
        if (value < 0 || value > 0xffff_ffffL) {
            throw new IllegalArgumentException("Q8 value is outside the unsigned word range");
        }
        return BigDecimal.valueOf(value)
                .divide(BigDecimal.valueOf(256L))
                .stripTrailingZeros()
                .toPlainString();
    }
}
