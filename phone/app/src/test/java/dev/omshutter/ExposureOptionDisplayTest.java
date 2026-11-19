package dev.omshutter;

import static org.junit.Assert.assertEquals;

import dev.omshutter.protocol.ExposureCatalogs;

import org.junit.Test;

public final class ExposureOptionDisplayTest {
    @Test
    public void formatsLongAndShortShutterLabelsWithSeconds() {
        assertEquals("10s", ExposureOptionDisplay.formatShutter("10\""));
        assertEquals("1/10s", ExposureOptionDisplay.formatShutter("10"));
        assertEquals("1/1.5s", ExposureOptionDisplay.formatShutter("1.5"));
        assertEquals("1/10s", ExposureOptionDisplay.formatShutter("1/10"));
        assertEquals("1.3s", ExposureOptionDisplay.formatShutter("1.3\""));
    }

    @Test
    public void preservesNonTimedShutterAndNonShutterLabels() {
        assertEquals("BULB", ExposureOptionDisplay.formatShutter("BULB"));
        assertEquals("F4.0", ExposureOptionDisplay.format(option(
                ExposureCatalogs.Kind.APERTURE, "F4.0")));
        assertEquals("3200", ExposureOptionDisplay.format(option(
                ExposureCatalogs.Kind.ISO, "3200")));
    }

    private static ExposureCatalogs.Option option(
            ExposureCatalogs.Kind kind, String label) {
        return new ExposureCatalogs.Option(kind, label);
    }
}
