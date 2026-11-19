package dev.omshutter.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class StarCaptureProtocolTest {
    private static final int SHUTTER_VALUE = -0x1234;
    private static final int APERTURE_VALUE = 0x2345;
    private static final int ISO_VALUE = 0x3456;
    private static final int FOCAL_BITS = Float.floatToRawIntBits(24.0f);

    @Test
    public void parsesMaximumPeakBatchWithoutSequenceOrCountFields() throws Exception {
        byte[] encoded = peaks(0, StarCaptureProtocol.MAX_PEAKS_PER_INDICATION, 0xffff_ffffL);
        assertEquals(1 + 63 * 8, encoded.length);

        StarCaptureProtocol.PeakBatch batch = (StarCaptureProtocol.PeakBatch)
                StarCaptureProtocol.parseControlIndication(encoded);
        assertEquals(63, batch.getPeaks().size());
        assertEquals(0, batch.getPeaks().get(0).getX());
        assertEquals(0, batch.getPeaks().get(0).getY());
        assertEquals(0xffff_ffffL, batch.getPeaks().get(0).getFlux());
        assertEquals(62, batch.getPeaks().get(62).getX());
        assertEquals(0xffff_ffc1L, batch.getPeaks().get(62).getFlux());
    }

    @Test
    public void peakBatchRejectsEmptyPartialOversizedCoordinatesAndIncreasingFlux() {
        assertProtocolError(
                "one or more",
                () -> StarCaptureProtocol.parseControlIndication(new byte[]{(byte) 0x81}));
        assertProtocolError(
                "complete XYF8",
                () -> StarCaptureProtocol.parseControlIndication(new byte[]{(byte) 0x81, 0}));
        assertProtocolError(
                "exceeds 63",
                () -> StarCaptureProtocol.parseControlIndication(peaks(0, 64, 1000)));

        byte[] badX = peaks(0, 1, 1000);
        putUnsignedShort(badX, 1, StarCaptureProtocol.IMAGE_WIDTH);
        assertProtocolError(
                "outside",
                () -> StarCaptureProtocol.parseControlIndication(badX));

        byte[] increasing = peaks(0, 2, 1000);
        putUnsignedWord(increasing, 1 + 8 + 4, 1001);
        assertProtocolError(
                "non-increasing",
                () -> StarCaptureProtocol.parseControlIndication(increasing));
    }

    @Test
    public void parsesSuccessfulTerminalWithAuthoritativeNativeMetadata() throws Exception {
        StarCaptureProtocol.Terminal terminal = (StarCaptureProtocol.Terminal)
                StarCaptureProtocol.parseControlIndication(successTerminal(
                        256,
                        SHUTTER_VALUE,
                        APERTURE_VALUE,
                        ISO_VALUE,
                        FOCAL_BITS,
                        "OLYMPUS M.12-45mm F4.0"));

        assertEquals(StarCaptureProtocol.Outcome.OK, terminal.getOutcome());
        assertEquals(256, terminal.getTotal());
        assertTrue(terminal.isSuccess());
        assertTrue(terminal.isExposureStateCertified());
        StarCaptureProtocol.CaptureMetadata metadata = terminal.getMetadata();
        assertEquals(SHUTTER_VALUE, metadata.getShutterValue());
        assertEquals(APERTURE_VALUE, metadata.getApertureValue());
        assertEquals(ISO_VALUE, metadata.getIsoValue());
        assertEquals(24.0f, metadata.getFocalLengthMm(), 0.0f);
        assertEquals("OLYMPUS M.12-45mm F4.0", metadata.getLensName());
    }

    @Test
    public void failureTerminalsAreCanonicalAndClassifyCleanupSafety() throws Exception {
        StarCaptureProtocol.Terminal rejected = terminal(1);
        assertEquals(StarCaptureProtocol.Outcome.REJECTED_NO_MUTATION, rejected.getOutcome());
        assertTrue(rejected.isExposureStateCertified());
        assertNull(rejected.getMetadata());

        StarCaptureProtocol.Terminal safe = terminal(2);
        assertEquals(StarCaptureProtocol.Outcome.FAILED_SAFE, safe.getOutcome());
        assertTrue(safe.isExposureStateCertified());

        StarCaptureProtocol.Terminal unsafe = terminal(3);
        assertEquals(StarCaptureProtocol.Outcome.FAILED_UNSAFE, unsafe.getOutcome());
        assertFalse(unsafe.isExposureStateCertified());

        assertProtocolError(
                "Unknown TERMINAL outcome",
                () -> StarCaptureProtocol.parseControlIndication(
                        new byte[]{(byte) 0x82, 4, 0, 0}));
        assertProtocolError(
                "zero total",
                () -> StarCaptureProtocol.parseControlIndication(
                        new byte[]{(byte) 0x82, 1, 1, 0}));
        assertProtocolError(
                "no state payload",
                () -> StarCaptureProtocol.parseControlIndication(
                        new byte[]{(byte) 0x82, 2, 0, 0, 0}));
    }

    @Test
    public void successTerminalRejectsMissingMalformedOrOversizedMetadata() {
        assertProtocolError(
                "omits authoritative",
                () -> StarCaptureProtocol.parseControlIndication(
                        new byte[]{(byte) 0x82, 0, 0, 0}));
        assertProtocolError(
                "metadata length",
                () -> StarCaptureProtocol.parseControlIndication(
                        new byte[]{(byte) 0x82, 0, 0, 0, 1}));
        assertProtocolError(
                "exceeds 256",
                () -> StarCaptureProtocol.parseControlIndication(successTerminal(
                        257, 1, 2, 3, 0, "")));
        assertProtocolError(
                "Focal length",
                () -> StarCaptureProtocol.parseControlIndication(successTerminal(
                        0, 1, 2, 3, 0x7f800000, "")));
    }

    static byte[] peaks(int first, int count, long firstFlux) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x81);
        for (int index = 0; index < count; index++) {
            int ordinal = first + index;
            writeUnsignedShort(output, ordinal % StarCaptureProtocol.IMAGE_WIDTH);
            writeUnsignedShort(output, ordinal % StarCaptureProtocol.IMAGE_HEIGHT);
            writeUnsignedWord(output, firstFlux - index);
        }
        return output.toByteArray();
    }

    static byte[] successTerminal(
            int total,
            int shutter,
            int aperture,
            int iso,
            int focalBits,
            String lens) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x82);
        output.write(0);
        writeUnsignedShort(output, total);
        writeSignedShort(output, shutter);
        writeSignedShort(output, aperture);
        writeSignedShort(output, iso);
        writeUnsignedWord(output, focalBits & 0xffff_ffffL);
        writeAscii(output, lens);
        return output.toByteArray();
    }

    private static StarCaptureProtocol.Terminal terminal(int outcome) throws Exception {
        return (StarCaptureProtocol.Terminal) StarCaptureProtocol.parseControlIndication(
                new byte[]{(byte) 0x82, (byte) outcome, 0, 0});
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        output.write(encoded.length);
        output.write(encoded, 0, encoded.length);
    }

    private static void writeSignedShort(ByteArrayOutputStream output, int value) {
        writeUnsignedShort(output, value & 0xffff);
    }

    private static void writeUnsignedShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void writeUnsignedWord(ByteArrayOutputStream output, long value) {
        output.write((int) value & 0xff);
        output.write((int) (value >>> 8) & 0xff);
        output.write((int) (value >>> 16) & 0xff);
        output.write((int) (value >>> 24) & 0xff);
    }

    private static void putUnsignedShort(byte[] output, int offset, int value) {
        output[offset] = (byte) value;
        output[offset + 1] = (byte) (value >>> 8);
    }

    private static void putUnsignedWord(byte[] output, int offset, long value) {
        output[offset] = (byte) value;
        output[offset + 1] = (byte) (value >>> 8);
        output[offset + 2] = (byte) (value >>> 16);
        output[offset + 3] = (byte) (value >>> 24);
    }

    private static String repeated(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }

    private static void assertProtocolError(String text, ThrowingAction action) {
        try {
            action.run();
            fail("Expected STAR capture protocol error containing: " + text);
        } catch (StarCaptureProtocol.ProtocolException expected) {
            assertTrue(
                    "Actual message: " + expected.getMessage(),
                    expected.getMessage().contains(text));
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }
}
