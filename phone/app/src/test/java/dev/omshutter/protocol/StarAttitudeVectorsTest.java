package dev.omshutter.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StarAttitudeVectorsTest {
    @Test
    public void stateReadVersionIsIndependentOfUnchangedWrites() {
        assertEquals(6, StarProtocol.CONFIG_VERSION);
        assertEquals(27, StarProtocol.STATE_WRITE_BYTES);
        assertEquals(7, StarProtocol.STATE_VERSION);
        assertEquals(125, StarProtocol.STATE_FIXED_BYTES);
        assertEquals(157, StarProtocol.STATE_MAX_BYTES);
        assertEquals(160, StarProtocol.MINIMUM_ATT_MTU);
    }

    @Test
    public void allGoldenStateTrailersDecodeWithStateFields() throws Exception {
        List<String> entries = entries(load("vectors/attitude-golden.json"));
        assertEquals(5, entries.size());
        for (String entry : entries) {
            byte[] wire = hex(string(entry, "hex"));
            StarProtocol.State state = StarProtocol.parseState(wire);
            GyroAttitudeProtocol.Snapshot attitude = state.getGyroAttitude();
            assertNotNull(attitude);
            String expected = entry.substring(entry.indexOf("\"gyro_attitude\""));
            assertEquals(integer(expected, "flags"), attitude.getFlags());
            assertEquals(integer(expected, "tick"), attitude.getTick());
            assertEquals(integer(expected, "capture_sequence"), attitude.getCaptureSequence());
            assertEquals(integer(expected, "capture_tick"), attitude.getCaptureTick());

            byte[] missingAttitude = Arrays.copyOf(wire, wire.length - GyroAttitudeProtocol.VALUE_BYTES);
            assertThrows(StarProtocol.ProtocolException.class,
                    () -> StarProtocol.parseState(missingAttitude));
            byte[] unsupported = wire.clone();
            unsupported[0] = 6;
            assertThrows(StarProtocol.ProtocolException.class,
                    () -> StarProtocol.parseState(unsupported));
        }
    }

    @Test
    public void malformedStateOrTrailerIsRejected() {
        List<String> entries = entries(load("vectors/attitude-invalid.json"));
        assertEquals(10, entries.size());
        for (String entry : entries) {
            assertThrows(string(entry, "name"), StarProtocol.ProtocolException.class,
                    () -> StarProtocol.parseState(hex(string(entry, "hex"))));
        }
    }

    private static List<String> entries(String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"name\"\\s*:").matcher(text);
        int start = -1;
        while (matcher.find()) {
            if (start >= 0) result.add(text.substring(start, matcher.start()));
            start = matcher.start();
        }
        if (start >= 0) result.add(text.substring(start));
        return result;
    }

    private static String string(String text, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(text);
        assertTrue("Missing " + name, matcher.find());
        return matcher.group(1);
    }

    private static long integer(String text, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*([0-9]+)").matcher(text);
        assertTrue("Missing " + name, matcher.find());
        return Long.parseLong(matcher.group(1));
    }

    private static byte[] hex(String text) {
        byte[] result = new byte[text.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static String load(String name) {
        String resource = "star-gatt/" + name;
        try (InputStream input = StarAttitudeVectorsTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new AssertionError("Missing resource " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("Cannot read " + resource, error);
        }
    }
}
