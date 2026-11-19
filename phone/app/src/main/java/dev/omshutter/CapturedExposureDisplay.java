package dev.omshutter;

import dev.omshutter.protocol.ExposureCatalogs;

/** Formats only exact labels that were bound to the successful capture operation. */
final class CapturedExposureDisplay {
    private CapturedExposureDisplay() {}

    static String format(ExposureCatalogs.Selection capturedSelection) {
        if (capturedSelection == null) {
            throw new IllegalArgumentException("captured selection is null");
        }
        return capturedSelection.getAperture()
                + " · " + ExposureOptionDisplay.formatShutter(capturedSelection.getShutter())
                + " · ISO " + capturedSelection.getIso();
    }
}
