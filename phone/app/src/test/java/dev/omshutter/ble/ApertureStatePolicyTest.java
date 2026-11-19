package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;

import dev.omshutter.protocol.ExposureCatalogs;
import dev.omshutter.protocol.StarCaptureProtocol;
import dev.omshutter.protocol.ExposureFixture;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ApertureStatePolicyTest {
    @Test
    public void boundChangeRequiresOneRecipeReadAndZeroCatalogReads() throws Exception {
        ExposureFixture before = state("F2.8", "F22", "F4.0");
        ExposureFixture after = state("F4.0", "F16", "F5.6");

        ApertureStatePolicy.Refresh refresh = ApertureStatePolicy.classify(
                catalogs(), before, after);

        assertEquals(ApertureStatePolicy.Refresh.RECIPE_ONLY, refresh);
        assertEquals(1, refresh.recipeReads());
        assertEquals(0, refresh.catalogReads());
    }

    @Test
    public void incoherentBoundsRequireFullCatalogRefresh() throws Exception {
        assertEquals(
                ApertureStatePolicy.Refresh.FULL_CATALOGS,
                ApertureStatePolicy.classify(
                        catalogs(),
                        state("F2.8", "F22", "F4.0"),
                        state("missing", "F16", "F5.6")));
        assertEquals(
                ApertureStatePolicy.Refresh.FULL_CATALOGS,
                ApertureStatePolicy.classify(
                        catalogs(),
                        null,
                        state("F16", "F4.0", "F5.6")));
    }

    @Test
    public void exposureChangeWithinSameBoundsNeedsNoRefresh() throws Exception {
        assertEquals(
                ApertureStatePolicy.Refresh.NONE,
                ApertureStatePolicy.classify(
                        catalogs(),
                        state("F2.8", "F22", "F4.0"),
                        state("F2.8", "F22", "F8.0")));
    }

    private static ExposureCatalogs catalogs() {
        return new ExposureCatalogs(
                Arrays.asList(new ExposureCatalogs.Option(
                        ExposureCatalogs.Kind.SHUTTER, "1/60")),
                Arrays.asList(
                        new ExposureCatalogs.Option(ExposureCatalogs.Kind.APERTURE, "F2.8"),
                        new ExposureCatalogs.Option(ExposureCatalogs.Kind.APERTURE, "F4.0"),
                        new ExposureCatalogs.Option(ExposureCatalogs.Kind.APERTURE, "F5.6"),
                        new ExposureCatalogs.Option(ExposureCatalogs.Kind.APERTURE, "F8.0"),
                        new ExposureCatalogs.Option(ExposureCatalogs.Kind.APERTURE, "F16"),
                        new ExposureCatalogs.Option(ExposureCatalogs.Kind.APERTURE, "F22")),
                Arrays.asList(new ExposureCatalogs.Option(
                        ExposureCatalogs.Kind.ISO, "800")));
    }

    private static ExposureFixture state(
            String minimum, String maximum, String current) throws Exception {
        return new ExposureFixture("1/60", current, "800", minimum, maximum);
    }

    private static void writeLabel(ByteArrayOutputStream output, String label) {
        byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
        output.write(bytes.length);
        output.write(bytes, 0, bytes.length);
    }

    private static void writeHalfword(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void writeWord(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }
}
