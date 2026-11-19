package dev.omshutter.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cross-runtime characterization against shared/contracts/star-gatt. */
public final class StarSharedVectorsTest {
    private static final String SCHEMA = load("star-gatt/schema.json");
    private static final String GOLDEN = load("star-gatt/vectors/golden.json");
    private static final String INVALID = load("star-gatt/vectors/invalid.json");

    @Test
    public void uuidsAndMtuMatchSharedSchema() {
        assertEquals(schemaUuid("star_service"), StarProtocol.SERVICE_UUID.toString());
        assertEquals(schemaUuid("control"), StarProtocol.CONTROL_UUID.toString());
        assertEquals(schemaUuid("state"), StarProtocol.STATE_UUID.toString());
        assertEquals(
                schemaUuid("shutter_catalog"),
                StarProtocol.SHUTTER_CATALOG_UUID.toString());
        assertEquals(
                schemaUuid("aperture_catalog"),
                StarProtocol.APERTURE_CATALOG_UUID.toString());
        assertEquals(schemaUuid("iso_catalog"), StarProtocol.ISO_CATALOG_UUID.toString());
        assertEquals(schemaUuid("lease"), StarProtocol.LEASE_UUID.toString());
        assertEquals(schemaInteger("minimum_att_mtu"), StarProtocol.MINIMUM_ATT_MTU);
        assertEquals(schemaInteger("state_fixed_size"), StarProtocol.STATE_FIXED_BYTES);
        assertEquals(schemaInteger("state_max_size"), StarProtocol.STATE_MAX_BYTES);
    }

    @Test
    public void catalogsAndLeaseMatchGoldenVectors() throws Exception {
        assertTrue(StarProtocol.parseExposureCatalog(
                ExposureCatalogs.Kind.SHUTTER,
                vector(GOLDEN, "unavailable_catalog")).isEmpty());
        List<ExposureCatalogs.Option> labels = StarProtocol.parseExposureCatalog(
                ExposureCatalogs.Kind.SHUTTER,
                vector(GOLDEN, "two_shutter_labels"));
        assertEquals("1s", labels.get(0).getLabel());
        assertEquals("2s", labels.get(1).getLabel());
        assertArrayEquals(
                vector(GOLDEN, "cancel_lease"), StarProtocol.encodeLeaseSeconds(0));
        assertArrayEquals(
                vector(GOLDEN, "phone_lease"), StarProtocol.encodeLeaseSeconds(45));
        assertArrayEquals(
                vector(GOLDEN, "maximum_lease"), StarProtocol.encodeLeaseSeconds(120));
    }

    @Test
    public void stateTelemetryAndLensCommandVectorsMatch() throws Exception {
        StarProtocol.State cold = StarProtocol.parseState(
                vector(GOLDEN, "cold_start_partial_state"));
        assertFalse(cold.hasCompleteMutableState());

        StarProtocol.State lensOnly = StarProtocol.parseState(
                vector(GOLDEN, "lens_command_only_valid"));
        assertEquals(null, lensOnly.getIbis());
        assertTrue(lensOnly.hasIbisAvailable());
        assertTrue(lensOnly.getLensCommand().isSourceMapped());
        assertTrue(lensOnly.getLensCommand().isSnapshotValid());
        assertEquals(2L, lensOnly.getLensCommand().getGeneration());
        assertEquals(
                Short.MIN_VALUE,
                lensOnly.getLensCommand().getCommandedYawMovestepRaw());
        assertEquals(
                Short.MAX_VALUE,
                lensOnly.getLensCommand().getCommandedPitchMovestepRaw());

        StarProtocol.State independent = StarProtocol.parseState(
                vector(GOLDEN, "q10_valid_lens_command_mapped_invalid"));
        assertEquals(100, independent.getIbis().getRawX());
        assertTrue(independent.getLensCommand().isSourceMapped());
        assertFalse(independent.getLensCommand().isSnapshotValid());

        StarProtocol.State complete = StarProtocol.parseState(
                vector(GOLDEN, "complete_idle_state"));
        assertTrue(complete.hasCompleteMutableState());
        assertEquals("M.ZUIKO 300mm", complete.getLensName());
        assertEquals(12L, complete.getLensCommand().getGeneration());
        assertEquals(-1149, complete.getLensCommand().getCommandedYawMovestepRaw());
        assertEquals(1149, complete.getLensCommand().getCommandedPitchMovestepRaw());
        assertTrue(complete.getAcceleration().isValid());
        assertEquals(10L, complete.getAcceleration().getMainGeneration());
        assertEquals(0, complete.getAcceleration().getXMillig());
        assertEquals(1_000, complete.getAcceleration().getYMillig());
        assertEquals(-10, complete.getAcceleration().getZMillig());
        assertTrue(StarProtocol.parseState(
                vector(GOLDEN, "recovery_target_state")).isRecoveryTarget());
    }

    @Test
    public void completeStateWriteMatchesGoldenVector() throws Exception {
        StarProtocol.State current = StarProtocol.parseState(
                vector(GOLDEN, "complete_idle_state"));
        ExposureCatalogs catalogs = indexedCatalogs();
        StarProtocol.MutationTarget target = StarProtocol.target(
                current,
                catalogs,
                new ExposureCatalogs.Selection("s1", "a2", "i3"),
                new ExposureCatalogs.Selection("s4", "a2", "i5"),
                current.getDetector());

        assertArrayEquals(
                vector(GOLDEN, "complete_compare_and_set_write"),
                StarProtocol.encodeStateWrite(target));
    }

    @Test
    public void everySharedInvalidCatalogAndStateIsRejected() {
        String[] catalogs = {
            "empty_catalog",
            "unavailable_catalog_with_trailer",
            "catalog_count_129",
            "catalog_duplicate_labels",
            "catalog_space_label"
        };
        for (String name : catalogs) {
            assertThrows(
                    name,
                    StarProtocol.ProtocolException.class,
                    () -> StarProtocol.parseExposureCatalog(
                            ExposureCatalogs.Kind.SHUTTER, vector(INVALID, name)));
        }

        String[] states = {
            "short_state",
            "state_wrong_version",
            "state_lens_length_mismatch",
            "state_catalog_flag_without_generation",
            "state_incomplete_with_config_generation",
            "state_unavailable_actual_nonzero",
            "state_reserved_outer_flag",
            "state_ibis_availability_mismatch",
            "state_ibis_unknown_flag",
            "state_lens_command_valid_without_source",
            "state_lens_command_valid_zero_generation",
            "state_lens_command_valid_odd_generation",
            "state_lens_command_invalid_nonzero_fields",
            "state_q10_absent_nonzero_sequence",
            "state_lens_command_outer_availability_mismatch"
        };
        for (String name : states) {
            assertThrows(
                    name,
                    StarProtocol.ProtocolException.class,
                    () -> StarProtocol.parseState(vector(INVALID, name)));
        }
    }

    private static ExposureCatalogs indexedCatalogs() {
        return new ExposureCatalogs(
                indexedOptions(ExposureCatalogs.Kind.SHUTTER, "s"),
                indexedOptions(ExposureCatalogs.Kind.APERTURE, "a"),
                indexedOptions(ExposureCatalogs.Kind.ISO, "i"));
    }

    private static List<ExposureCatalogs.Option> indexedOptions(
            ExposureCatalogs.Kind kind, String prefix) {
        ArrayList<ExposureCatalogs.Option> options = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            options.add(new ExposureCatalogs.Option(kind, prefix + index));
        }
        return options;
    }

    private static String schemaUuid(String name) {
        Matcher matcher = Pattern.compile(
                "\\\"" + Pattern.quote(name)
                        + "\\\"\\s*:\\s*\\\"([0-9a-f-]{36})\\\"")
                .matcher(SCHEMA);
        if (!matcher.find()) throw new AssertionError("Missing shared UUID " + name);
        return matcher.group(1);
    }

    private static int schemaInteger(String name) {
        Matcher matcher = Pattern.compile(
                "\\\"" + Pattern.quote(name) + "\\\"\\s*:\\s*([0-9]+)")
                .matcher(SCHEMA);
        if (!matcher.find()) throw new AssertionError("Missing shared integer " + name);
        return Integer.parseInt(matcher.group(1));
    }

    private static byte[] vector(String document, String name) {
        String marker = "\"name\": \"" + name + "\"";
        int start = document.indexOf(marker);
        if (start < 0) throw new AssertionError("Missing shared vector " + name);
        int end = document.indexOf("\"name\": \"", start + marker.length());
        String entry = document.substring(start, end < 0 ? document.length() : end);
        Matcher matcher = Pattern.compile("\"hex\"\\s*:\\s*\"([0-9a-f]*)\"")
                .matcher(entry);
        if (!matcher.find()) throw new AssertionError("Missing hex for shared vector " + name);
        String compact = matcher.group(1);
        byte[] value = new byte[compact.length() / 2];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) Integer.parseInt(
                    compact.substring(index * 2, index * 2 + 2), 16);
        }
        return value;
    }

    private static String load(String resource) {
        try (InputStream input = StarSharedVectorsTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new AssertionError("Missing shared resource " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("Cannot read " + resource, error);
        }
    }
}
