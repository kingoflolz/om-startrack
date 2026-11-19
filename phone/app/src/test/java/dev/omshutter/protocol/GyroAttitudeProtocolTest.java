package dev.omshutter.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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

/** The same checked-in wire packets exercise Python, C/C++, and Android. */
public final class GyroAttitudeProtocolTest {
    @Test
    public void constantsMatchSharedContract() {
        String schema = load("schema.json");
        assertEquals(integer(schema, "version"), GyroAttitudeProtocol.VERSION);
        assertEquals(integer(schema, "value_size"), GyroAttitudeProtocol.VALUE_BYTES);
        assertEquals(integer(schema, "nominal_tick_hz"), GyroAttitudeProtocol.TICK_HZ);
    }

    @Test
    public void allGoldenPacketsDecodeAndNormalize() throws Exception {
        List<String> entries = entries(load("vectors/golden.json"));
        assertEquals(10, entries.size());
        for (String entry : entries) {
            GyroAttitudeProtocol.Snapshot value = GyroAttitudeProtocol.parse(hex(string(entry, "hex")));
            int flags = (int) integer(entry, "flags");
            assertEquals(integer(entry, "tick"), value.getTick());
            assertEquals(integer(entry, "capture_sequence"), value.getCaptureSequence());
            assertEquals(integer(entry, "capture_tick"), value.getCaptureTick());
            assertEquals(flags, value.getFlags());
            assertEquals((flags & 1) != 0, value.isCurrentValid());
            assertEquals((flags & 2) != 0, value.isCaptureValid());
            assertEquals((flags & 4) != 0, value.isCaptureTimingPreparation());
            checkQuaternion((flags & 1) != 0, lanes(entry, "current_q30"),
                    value.getCurrentQ30(), value.getCurrentQuaternion());
            checkQuaternion((flags & 2) != 0, lanes(entry, "capture_q30"),
                    value.getCaptureQ30(), value.getCaptureQuaternion());
        }
    }

    @Test
    public void everyMalformedPacketIsRejected() {
        List<String> entries = entries(load("vectors/invalid.json"));
        assertEquals(13, entries.size());
        for (String entry : entries) {
            assertThrows(string(entry, "name"), GyroAttitudeProtocol.ProtocolException.class,
                    () -> GyroAttitudeProtocol.parse(hex(string(entry, "hex"))));
        }
        assertThrows(GyroAttitudeProtocol.ProtocolException.class,
                () -> GyroAttitudeProtocol.parse(null));
    }

    @Test
    public void snapshotArraysDoNotExposeMutableState() throws Exception {
        String entry = entries(load("vectors/golden.json")).get(2);
        byte[] input = hex(string(entry, "hex"));
        GyroAttitudeProtocol.Snapshot value = GyroAttitudeProtocol.parse(input);
        input[16] ^= 0x7f;
        int[] originalCurrent = value.getCurrentQ30();
        int[] originalCapture = value.getCaptureQ30();
        value.getCurrentQ30()[0] = 0;
        value.getCaptureQ30()[0] = 123;
        value.getCurrentQuaternion()[0] = 100;
        value.getCaptureQuaternion()[0] = 100;
        assertArrayEquals(originalCurrent, value.getCurrentQ30());
        assertArrayEquals(originalCapture, value.getCaptureQ30());
        assertEquals(0.5, value.getCurrentQuaternion()[0], 1e-12);
        assertEquals(0, value.getCaptureQuaternion()[0], 0);
    }

    private static void checkQuaternion(boolean valid, int[] expected, int[] actual, double[] q) {
        if (!valid) {
            assertNull(actual);
            assertNull(q);
            return;
        }
        assertArrayEquals(expected, actual);
        double normSquared = 0;
        for (double component : q) normSquared += component * component;
        assertEquals(1.0, normSquared, 1e-12);
        for (int index = 0; index < 4; index++) {
            assertEquals(Math.signum(expected[index]), Math.signum(q[index]), 0);
        }
    }

    private static int[] lanes(String entry, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\\[([^]]*)]").matcher(entry);
        assertTrue("Missing " + name, matcher.find());
        String[] values = matcher.group(1).split(",");
        assertEquals(4, values.length);
        int[] result = new int[4];
        for (int index = 0; index < 4; index++) result[index] = Integer.parseInt(values[index].trim());
        return result;
    }

    private static List<String> entries(String text) {
        List<String> entries = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"name\"\\s*:").matcher(text);
        int start = -1;
        while (matcher.find()) {
            if (start >= 0) entries.add(text.substring(start, matcher.start()));
            start = matcher.start();
        }
        if (start >= 0) entries.add(text.substring(start));
        return entries;
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
        byte[] value = new byte[text.length() / 2];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
        }
        return value;
    }

    private static String load(String name) {
        String resource = "gyro-attitude-v1/" + name;
        try (InputStream input = GyroAttitudeProtocolTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new AssertionError("Missing resource " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("Cannot read " + resource, error);
        }
    }
}
