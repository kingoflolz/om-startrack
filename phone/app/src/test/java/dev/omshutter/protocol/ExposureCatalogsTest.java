package dev.omshutter.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ExposureCatalogsTest {
    @Test
    public void exposesImmutablePerKindCatalogsAndLabelLookup() {
        ExposureCatalogs catalogs = catalogs();

        assertEquals(Arrays.asList("1/4", "1/8"), labels(catalogs.get(
                ExposureCatalogs.Kind.SHUTTER)));
        assertSame(
                catalogs.get(ExposureCatalogs.Kind.APERTURE).get(1),
                catalogs.findByLabel(ExposureCatalogs.Kind.APERTURE, "F4.5"));
        assertNull(catalogs.findByLabel(ExposureCatalogs.Kind.ISO, "6400"));

        try {
            catalogs.get(ExposureCatalogs.Kind.ISO).add(
                    option(ExposureCatalogs.Kind.ISO, "6400"));
            fail("catalog list was mutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void selectionUsesExactLabelsAndMatchesCameraState() throws Exception {
        ExposureCatalogs catalogs = catalogs();
        ExposureCatalogs.Selection selection = catalogs.toSelection(
                catalogs.findByLabel(ExposureCatalogs.Kind.SHUTTER, "1/8"),
                catalogs.findByLabel(ExposureCatalogs.Kind.APERTURE, "F4.0"),
                catalogs.findByLabel(ExposureCatalogs.Kind.ISO, "3200"));

        assertEquals("1/8", selection.getShutter());
        assertTrue(selection.matches(cameraState("1/8", "F4.0", "3200")));
        assertFalse(selection.matches(cameraState("1/4", "F4.0", "3200")));
        assertEquals(selection, ExposureCatalogs.Selection.from(
                cameraState("1/8", "F4.0", "3200")));
    }

    @Test
    public void rejectsNonCameraLabelsDuplicatesAndForeignMembership() {
        expectIllegal(() -> option(ExposureCatalogs.Kind.SHUTTER, ""));
        expectIllegal(() -> option(ExposureCatalogs.Kind.SHUTTER, "has space"));
        expectIllegal(() -> option(ExposureCatalogs.Kind.SHUTTER, "café"));
        expectIllegal(() -> option(
                ExposureCatalogs.Kind.SHUTTER,
                "12345678901234567890123456789012"));

        expectIllegal(() -> new ExposureCatalogs(
                Arrays.asList(
                        option(ExposureCatalogs.Kind.SHUTTER, "1/4"),
                        option(ExposureCatalogs.Kind.SHUTTER, "1/4")),
                Collections.singletonList(option(ExposureCatalogs.Kind.APERTURE, "F4.0")),
                Collections.singletonList(option(ExposureCatalogs.Kind.ISO, "1600"))));

        ExposureCatalogs catalogs = catalogs();
        expectIllegal(() -> catalogs.toSelection(
                option(ExposureCatalogs.Kind.SHUTTER, "1/1000"),
                catalogs.get(ExposureCatalogs.Kind.APERTURE).get(0),
                catalogs.get(ExposureCatalogs.Kind.ISO).get(0)));
    }

    @Test
    public void enforcesPerKindCountAndEncodedSizeBounds() {
        expectIllegal(() -> new ExposureCatalogs(
                Collections.emptyList(),
                Collections.singletonList(option(ExposureCatalogs.Kind.APERTURE, "F4.0")),
                Collections.singletonList(option(ExposureCatalogs.Kind.ISO, "1600"))));

        ArrayList<ExposureCatalogs.Option> tooMany = new ArrayList<>();
        for (int index = 0; index < 129; index++) {
            tooMany.add(option(ExposureCatalogs.Kind.SHUTTER, "V" + index));
        }
        expectIllegal(() -> new ExposureCatalogs(
                tooMany,
                Collections.singletonList(option(ExposureCatalogs.Kind.APERTURE, "F4.0")),
                Collections.singletonList(option(ExposureCatalogs.Kind.ISO, "1600"))));

        ArrayList<ExposureCatalogs.Option> tooLarge = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            tooLarge.add(option(
                    ExposureCatalogs.Kind.SHUTTER,
                    String.format("%02d-1234567890123456789012345678", index)));
        }
        expectIllegal(() -> new ExposureCatalogs(
                tooLarge,
                Collections.singletonList(option(ExposureCatalogs.Kind.APERTURE, "F4.0")),
                Collections.singletonList(option(ExposureCatalogs.Kind.ISO, "1600"))));
    }

    @Test
    public void allowsAggregateAbove512WhenEachCharacteristicFits() {
        List<ExposureCatalogs.Option> shutter = mediumCatalog(
                ExposureCatalogs.Kind.SHUTTER, "S");
        List<ExposureCatalogs.Option> aperture = mediumCatalog(
                ExposureCatalogs.Kind.APERTURE, "A");
        List<ExposureCatalogs.Option> iso = mediumCatalog(
                ExposureCatalogs.Kind.ISO, "I");

        ExposureCatalogs catalogs = new ExposureCatalogs(shutter, aperture, iso);

        assertEquals(20, catalogs.get(ExposureCatalogs.Kind.SHUTTER).size());
        assertEquals(20, catalogs.get(ExposureCatalogs.Kind.APERTURE).size());
        assertEquals(20, catalogs.get(ExposureCatalogs.Kind.ISO).size());
    }

    private static ExposureCatalogs catalogs() {
        return new ExposureCatalogs(
                Arrays.asList(
                        option(ExposureCatalogs.Kind.SHUTTER, "1/4"),
                        option(ExposureCatalogs.Kind.SHUTTER, "1/8")),
                Arrays.asList(
                        option(ExposureCatalogs.Kind.APERTURE, "F4.0"),
                        option(ExposureCatalogs.Kind.APERTURE, "F4.5")),
                Arrays.asList(
                        option(ExposureCatalogs.Kind.ISO, "1600"),
                        option(ExposureCatalogs.Kind.ISO, "3200")));
    }

    private static List<ExposureCatalogs.Option> mediumCatalog(
            ExposureCatalogs.Kind kind,
            String prefix) {
        ArrayList<ExposureCatalogs.Option> result = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            result.add(option(
                    kind,
                    prefix + String.format("%02d-1234567890123456", index)));
        }
        return result;
    }

    private static ExposureCatalogs.Option option(
            ExposureCatalogs.Kind kind,
            String label) {
        return new ExposureCatalogs.Option(kind, label);
    }

    private static List<String> labels(List<ExposureCatalogs.Option> options) {
        ArrayList<String> labels = new ArrayList<>();
        for (ExposureCatalogs.Option option : options) labels.add(option.getLabel());
        return labels;
    }

    private static ExposureFixture cameraState(
            String shutter,
            String aperture,
            String iso) throws Exception {
        return new ExposureFixture(shutter, aperture, iso, aperture, aperture);
    }

    private static void writeText(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        output.write(bytes.length);
        output.write(bytes, 0, bytes.length);
    }

    private static void expectIllegal(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
