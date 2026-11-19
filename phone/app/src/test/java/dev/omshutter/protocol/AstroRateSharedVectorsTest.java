package dev.omshutter.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import dev.omshutter.solver.SiderealRateCorrection;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cross-runtime characterization against shared/contracts/astro-rate-gatt-v2. */
public final class AstroRateSharedVectorsTest {
    private static final String SCHEMA = load("astro-rate-gatt-v2/schema.json");
    private static final String GOLDEN = load("astro-rate-gatt-v2/vectors/golden.json");
    private static final String INVALID = load("astro-rate-gatt-v2/vectors/invalid.json");

    @Test
    public void uuidConstantsMatchSharedSchema() {
        assertEquals(schemaUuid("service"), AstroRateGattProtocol.SERVICE_UUID.toString());
        assertEquals(schemaUuid("rate"), AstroRateGattProtocol.RATE_UUID.toString());
    }

    @Test
    public void integratedLeasePolicyMatchesSharedSchema() {
        assertEquals(schemaInteger("integrated_ttl_ticks"), AstroRateGattProtocol.APP_TTL_TICKS);
        assertEquals(
                (long) schemaInteger("integrated_renewal_ms"),
                AstroRateGattProtocol.APP_RENEWAL_MS);
    }

    @Test
    public void integratedSetpointMatchesSharedGoldenVector() {
        SiderealRateCorrection correction =
                SiderealRateCorrection.fromBodyAngularVelocity(
                        0.0, -SiderealRateCorrection.SIDEREAL_RATE_RAD_PER_SECOND, 0.0);
        assertArrayEquals(
                vector("active.equator_roll_zero"),
                AstroRateGattProtocol.setpoint(
                        correction, AstroRateGattProtocol.APP_TTL_TICKS));
    }

    @Test
    public void exactCancelMatchesSharedGoldenVector() {
        assertArrayEquals(vector("cancel.exact_zero"), AstroRateGattProtocol.cancel());
    }

    @Test
    public void unsupportedGaveVectorsCannotReachTheWire() {
        for (String id : new String[]{
                "unsupported.gave_only",
                "unsupported.gave0_offset.-1", "unsupported.gave0_offset.+1",
                "unsupported.gave1_offset.-1", "unsupported.gave1_offset.+1"}) {
            byte[] value = invalidVector(id);
            assertThrows(id, IllegalArgumentException.class,
                    () -> AstroRateGattProtocol.setpointV2(
                            setpointFrom(value), u16(value, 12)));
        }
    }

    @Test
    public void everyActiveSharedGoldenVectorIsProducedByTypedV2Api() {
        assertArrayEquals(
                vector("active.v1_promoted_equator_roll_zero"),
                AstroRateGattProtocol.setpointV2(
                        AstroRateSetpoint.bodyOnly(
                                SiderealRateCorrection.fromLegacyWireCounts(-729, 0, 0)),
                        10_000));
        assertArrayEquals(
                vector("active.v1_promoted_solved_fixture"),
                AstroRateGattProtocol.setpointV2(
                        AstroRateSetpoint.bodyOnly(
                                SiderealRateCorrection.fromLegacyWireCounts(-333, 118, -638)),
                        10_000));
        assertArrayEquals(
                vector("active.component_and_unsigned_maxima"),
                AstroRateGattProtocol.setpointV2(
                        new AstroRateSetpoint(
                                SiderealRateCorrection.fromQ16WireCounts(
                                        SiderealRateCorrection.MAX_WIRE_Q16,
                                        -SiderealRateCorrection.MAX_WIRE_Q16,
                                        -1),
                                0,
                                0),
                        0xffff));
        assertArrayEquals(
                vector("active.minimum_nonzero"),
                AstroRateGattProtocol.setpointV2(
                        AstroRateSetpoint.bodyOnly(
                                SiderealRateCorrection.fromQ16WireCounts(1, 0, 0)),
                        1));
    }

    @Test
    public void typedApiRejectsEveryRepresentableSharedInvalidClass() {
        SiderealRateCorrection one = SiderealRateCorrection.fromQ16WireCounts(1, 0, 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> SiderealRateCorrection.fromQ16WireCounts(
                        SiderealRateCorrection.MAX_WIRE_Q16 + 1, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> SiderealRateCorrection.fromQ16WireCounts(
                        -SiderealRateCorrection.MAX_WIRE_Q16 - 1, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> AstroRateGattProtocol.setpointV2(
                        AstroRateSetpoint.bodyOnly(one), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> AstroRateGattProtocol.setpointV2(
                        AstroRateSetpoint.gaveOnly(0, 0), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AstroRateSetpoint(one, 65, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AstroRateSetpoint(one, -65, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AstroRateSetpoint(one, 0, 65));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AstroRateSetpoint(one, 0, -65));
    }

    @Test
    public void everySharedInvalidVectorIsCoveredByTheTypedEncoderBoundary() {
        assertNotEquals(
                AstroRateGattProtocol.VALUE_V2_BYTES,
                invalidVector("length.short").length);
        assertNotEquals(
                AstroRateGattProtocol.VALUE_V2_BYTES,
                invalidVector("length.trailing").length);

        assertThrows(
                IllegalArgumentException.class,
                () -> correctionFrom(invalidVector("rate.above_max")));
        assertThrows(
                IllegalArgumentException.class,
                () -> correctionFrom(invalidVector("rate.below_min")));

        byte[] inactive = invalidVector("active.requires_nonzero_body_or_gave");
        assertThrows(
                IllegalArgumentException.class,
                () -> AstroRateGattProtocol.setpointV2(
                        AstroRateSetpoint.gaveOnly(0, 0), u16(inactive, 12)));

        byte[] zeroTtl = invalidVector("ttl.zero_active");
        assertThrows(
                IllegalArgumentException.class,
                () -> AstroRateGattProtocol.setpointV2(
                        AstroRateSetpoint.bodyOnly(correctionFrom(zeroTtl)),
                        u16(zeroTtl, 12)));

        assertFalse(java.util.Arrays.equals(
                AstroRateGattProtocol.cancelV2(),
                invalidVector("cancel.must_be_exact_zero")));

        assertThrows(
                IllegalArgumentException.class,
                () -> setpointFrom(invalidVector("gave0.above_admission_bound")));
        assertThrows(
                IllegalArgumentException.class,
                () -> setpointFrom(invalidVector("gave0.below_admission_bound")));
        assertThrows(
                IllegalArgumentException.class,
                () -> setpointFrom(invalidVector("gave1.above_admission_bound")));
        assertThrows(
                IllegalArgumentException.class,
                () -> setpointFrom(invalidVector("gave1.below_admission_bound")));
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

    private static byte[] vector(String id) {
        return vector(GOLDEN, id);
    }

    private static byte[] invalidVector(String id) {
        return vector(INVALID, id);
    }

    private static byte[] vector(String document, String id) {
        String marker = "\"id\": \"" + id + "\"";
        int start = document.indexOf(marker);
        if (start < 0) throw new AssertionError("Missing shared vector " + id);
        int end = document.indexOf("\"id\": \"", start + marker.length());
        String entry = document.substring(start, end < 0 ? document.length() : end);
        Matcher matcher = Pattern.compile("\"hex\"\\s*:\\s*\"([0-9a-f]*)\"")
                .matcher(entry);
        if (!matcher.find()) throw new AssertionError("Missing hex for shared vector " + id);
        String compact = matcher.group(1);
        byte[] value = new byte[compact.length() / 2];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) Integer.parseInt(
                    compact.substring(index * 2, index * 2 + 2), 16);
        }
        return value;
    }

    private static AstroRateSetpoint setpointFrom(byte[] value) {
        return new AstroRateSetpoint(
                correctionFrom(value), i16(value, 14), i16(value, 16));
    }

    private static SiderealRateCorrection correctionFrom(byte[] value) {
        return SiderealRateCorrection.fromQ16WireCounts(
                i32(value, 0), i32(value, 4), i32(value, 8));
    }

    private static int u16(byte[] value, int offset) {
        return (value[offset] & 0xff) | ((value[offset + 1] & 0xff) << 8);
    }

    private static int i16(byte[] value, int offset) {
        return (short) u16(value, offset);
    }

    private static int i32(byte[] value, int offset) {
        return (value[offset] & 0xff)
                | ((value[offset + 1] & 0xff) << 8)
                | ((value[offset + 2] & 0xff) << 16)
                | (value[offset + 3] << 24);
    }

    private static String load(String resource) {
        try (InputStream input = AstroRateSharedVectorsTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new AssertionError("Missing shared resource " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("Cannot read " + resource, error);
        }
    }
}
