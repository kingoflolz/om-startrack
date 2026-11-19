package dev.omshutter;

import java.util.Locale;

/** Stable UI formatting for camera-reported focal lengths. */
final class FocalLengthDisplay {
    private FocalLengthDisplay() {}

    static String format(float focalLengthMm) {
        return String.format(Locale.US, "%.2f", focalLengthMm);
    }
}
