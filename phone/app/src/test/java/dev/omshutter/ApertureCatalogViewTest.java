package dev.omshutter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import dev.omshutter.protocol.ExposureCatalogs;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class ApertureCatalogViewTest {
    @Test
    public void keepsInclusiveExactLabelRangeInCameraOrder() {
        ExposureCatalogs full = catalogs();

        ExposureCatalogs filtered = ApertureCatalogView.filter(
                full, "4.0", "22", "5.6");

        assertEquals(
                Arrays.asList("4.0", "5.6", "8.0", "11", "16", "22"),
                labels(filtered.get(ExposureCatalogs.Kind.APERTURE)));
        assertEquals(
                labels(full.get(ExposureCatalogs.Kind.SHUTTER)),
                labels(filtered.get(ExposureCatalogs.Kind.SHUTTER)));
        assertEquals(
                labels(full.get(ExposureCatalogs.Kind.ISO)),
                labels(filtered.get(ExposureCatalogs.Kind.ISO)));
    }

    @Test
    public void rejectsMissingReversedOrOutOfRangeStateLabels() {
        ExposureCatalogs full = catalogs();

        assertNull(ApertureCatalogView.filter(full, "missing", "22", "5.6"));
        assertNull(ApertureCatalogView.filter(full, "22", "4.0", "5.6"));
        assertNull(ApertureCatalogView.filter(full, "4.0", "22", "27"));
    }

    @Test
    public void returnsExistingViewWhenBoundsAlreadySpanIt() {
        ExposureCatalogs full = catalogs();
        assertSame(full, ApertureCatalogView.filter(full, "2.8", "27", "8.0"));
    }

    private static ExposureCatalogs catalogs() {
        return new ExposureCatalogs(
                Arrays.asList(option(ExposureCatalogs.Kind.SHUTTER, "1/4")),
                Arrays.asList(
                        option(ExposureCatalogs.Kind.APERTURE, "2.8"),
                        option(ExposureCatalogs.Kind.APERTURE, "4.0"),
                        option(ExposureCatalogs.Kind.APERTURE, "5.6"),
                        option(ExposureCatalogs.Kind.APERTURE, "8.0"),
                        option(ExposureCatalogs.Kind.APERTURE, "11"),
                        option(ExposureCatalogs.Kind.APERTURE, "16"),
                        option(ExposureCatalogs.Kind.APERTURE, "22"),
                        option(ExposureCatalogs.Kind.APERTURE, "27")),
                Arrays.asList(option(ExposureCatalogs.Kind.ISO, "1600")));
    }

    private static ExposureCatalogs.Option option(
            ExposureCatalogs.Kind kind, String label) {
        return new ExposureCatalogs.Option(kind, label);
    }

    private static List<String> labels(List<ExposureCatalogs.Option> options) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (ExposureCatalogs.Option option : options) result.add(option.getLabel());
        return result;
    }
}
