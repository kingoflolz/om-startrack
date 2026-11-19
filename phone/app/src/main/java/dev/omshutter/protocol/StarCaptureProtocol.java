package dev.omshutter.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Encoder/decoder for STAR capture commands, peaks, and completion metadata.
 *
 * <p>GATT supplies framing, integrity, and ordered delivery. Consequently the wire format has
 * no application envelope, sequence, job, checksum, receipt, or acknowledgement fields.
 */
public final class StarCaptureProtocol {
    public static final UUID APPLICATION_UUID =
            UUID.fromString("7e6d7a50-2c56-4f17-9c51-9a5f64b02400");
    public static final UUID SERVICE_UUID =
            UUID.fromString("7e6d7a50-2c56-4f17-9c51-9a5f64b02401");
    public static final UUID CONTROL_UUID =
            UUID.fromString("7e6d7a50-2c56-4f17-9c51-9a5f64b02402");
    public static final UUID CLIENT_CONFIGURATION_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final int CAPTURE_RECIPE = 0x01;
    public static final int PEAKS = 0x81;
    public static final int DONE = 0x82;

    public static final int MAX_PEAKS = 256;
    public static final int MAX_PEAKS_PER_INDICATION = 63;
    public static final int IMAGE_WIDTH = 1296;
    public static final int IMAGE_HEIGHT = 972;
    public static final int MAX_EXPOSURE_LABEL_BYTES = 31;
    public static final int MAX_EXPOSURE_OPTIONS = 128;
    public static final int MAX_ATTRIBUTE_VALUE_BYTES = 512;
    public static final int MAX_LENS_NAME_BYTES = 32;
    public static final float MIN_FOCAL_LENGTH_MM = 0.1f;
    public static final float MAX_FOCAL_LENGTH_MM = 2047.96875f;

    private static final int PEAK_BYTES = 8;
    private static final int TERMINAL_FIXED_BYTES = 4;

    private StarCaptureProtocol() {}

    /** Returns the complete CAPTURE_RECIPE command value. */
    public static byte[] captureRecipeRequest() {
        return new byte[]{(byte) CAPTURE_RECIPE};
    }

    /** CCCD value required on CONTROL before CAPTURE_RECIPE is written. */
    public static byte[] controlSubscriptionValue() {
        return new byte[]{0x02, 0x00};
    }

    /** Optional CCCD value used to receive latest-value STATE notifications. */
    public static byte[] stateSubscriptionValue() {
        return new byte[]{0x01, 0x00};
    }

    /** Parses one confirmed indication from the CONTROL characteristic. */
    public static ControlIndication parseControlIndication(byte[] value)
            throws ProtocolException {
        if (value == null || value.length == 0) {
            throw new ProtocolException("CONTROL indication is empty");
        }
        int type = unsignedByte(value[0]);
        if (type == PEAKS) return parsePeakBatch(value);
        if (type == DONE) return parseTerminal(value);
        throw new ProtocolException(String.format(
                "Unknown CONTROL indication type 0x%02x", type));
    }

    private static PeakBatch parsePeakBatch(byte[] value) throws ProtocolException {
        int payloadBytes = value.length - 1;
        if (payloadBytes == 0 || payloadBytes % PEAK_BYTES != 0) {
            throw new ProtocolException(
                    "PEAKS payload must contain one or more complete XYF8 records");
        }
        int count = payloadBytes / PEAK_BYTES;
        if (count > MAX_PEAKS_PER_INDICATION) {
            throw new ProtocolException("PEAKS indication exceeds 63 records");
        }

        ArrayList<Peak> peaks = new ArrayList<>(count);
        long previousFlux = 0xffff_ffffL;
        int offset = 1;
        for (int index = 0; index < count; index++, offset += PEAK_BYTES) {
            int x = unsignedShort(value, offset);
            int y = unsignedShort(value, offset + 2);
            long flux = unsignedWord(value, offset + 4);
            if (x >= IMAGE_WIDTH || y >= IMAGE_HEIGHT) {
                throw new ProtocolException("PEAKS coordinate is outside 1296x972");
            }
            if (flux > previousFlux) {
                throw new ProtocolException("PEAKS flux is not non-increasing");
            }
            peaks.add(new Peak(x, y, flux));
            previousFlux = flux;
        }
        return new PeakBatch(peaks);
    }

    private static Terminal parseTerminal(byte[] value) throws ProtocolException {
        if (value.length < TERMINAL_FIXED_BYTES) {
            throw new ProtocolException("TERMINAL indication is truncated");
        }
        Outcome outcome = Outcome.fromWire(unsignedByte(value[1]));
        int total = unsignedShort(value, 2);
        if (outcome == Outcome.OK) {
            if (total > MAX_PEAKS) {
                throw new ProtocolException("TERMINAL total exceeds 256 peaks");
            }
            if (value.length == TERMINAL_FIXED_BYTES) {
                throw new ProtocolException("Successful DONE omits authoritative metadata");
            }
            CaptureMetadata metadata = parseCaptureMetadata(
                    value, TERMINAL_FIXED_BYTES, value.length - TERMINAL_FIXED_BYTES);
            return new Terminal(outcome, total, metadata);
        }

        if (total != 0 || value.length != TERMINAL_FIXED_BYTES) {
            throw new ProtocolException(
                    "Failed TERMINAL must have zero total and no state payload");
        }
        return new Terminal(outcome, 0, null);
    }

    private static CaptureMetadata parseCaptureMetadata(byte[] value, int offset, int length)
            throws ProtocolException {
        if (length < 11 || length > 43) {
            throw new ProtocolException("Successful TERMINAL metadata length is outside 11..43 bytes");
        }
        int end = offset + length;
        if (offset < 0 || length < 0 || end < offset || end > value.length) {
            throw new ProtocolException("TERMINAL metadata bounds are invalid");
        }
        int shutterValue = signedShort(value, offset);
        int apertureValue = signedShort(value, offset + 2);
        int isoValue = signedShort(value, offset + 4);
        int focalLengthBits = (int) unsignedWord(value, offset + 6);
        validateFocalLength(focalLengthBits);
        Cursor cursor = new Cursor(offset + 10, end);
        String lens = cursor.readAscii(
                value, "DONE", "lens", 0, MAX_LENS_NAME_BYTES, true);
        if (cursor.offset != end) {
            throw new ProtocolException("Successful TERMINAL has trailing metadata bytes");
        }
        return new CaptureMetadata(
                shutterValue, apertureValue, isoValue, focalLengthBits, lens);
    }

    private static void validateFocalLength(int bits) throws ProtocolException {
        if (bits == 0) return;
        float focalLength = Float.intBitsToFloat(bits);
        if (Float.isNaN(focalLength)
                || Float.isInfinite(focalLength)
                || focalLength < MIN_FOCAL_LENGTH_MM
                || focalLength > MAX_FOCAL_LENGTH_MM) {
            throw new ProtocolException("Focal length is outside the canonical range");
        }
    }

    private static int unsignedByte(byte value) {
        return value & 0xff;
    }

    private static int unsignedShort(byte[] value, int offset) {
        return unsignedByte(value[offset]) | (unsignedByte(value[offset + 1]) << 8);
    }

    private static int signedShort(byte[] value, int offset) {
        return (short) unsignedShort(value, offset);
    }

    private static long unsignedWord(byte[] value, int offset) {
        return (long) unsignedByte(value[offset])
                | ((long) unsignedByte(value[offset + 1]) << 8)
                | ((long) unsignedByte(value[offset + 2]) << 16)
                | ((long) unsignedByte(value[offset + 3]) << 24);
    }

    private static final class Cursor {
        private int offset;
        private final int end;

        private Cursor(int offset, int end) {
            this.offset = offset;
            this.end = end;
        }

        private String readAscii(
                byte[] value,
                String context,
                String field,
                int minimumLength,
                int maximumLength,
                boolean allowSpace)
                throws ProtocolException {
            if (offset >= end) {
                throw new ProtocolException(context + " omits " + field + " length");
            }
            int length = unsignedByte(value[offset++]);
            if (length < minimumLength || length > maximumLength) {
                throw new ProtocolException(context + " " + field + " length is invalid");
            }
            if (offset + length > end) {
                throw new ProtocolException(context + " " + field + " is truncated");
            }
            int minimumByte = allowSpace ? 0x20 : 0x21;
            for (int index = offset; index < offset + length; index++) {
                int character = unsignedByte(value[index]);
                if (character < minimumByte || character > 0x7e) {
                    throw new ProtocolException(
                            context + " " + field + " is not printable ASCII");
                }
            }
            String result = new String(value, offset, length, StandardCharsets.US_ASCII);
            offset += length;
            return result;
        }
    }

    public enum Outcome {
        OK(0, true),
        REJECTED_NO_MUTATION(1, true),
        FAILED_SAFE(2, true),
        FAILED_UNSAFE(3, false);

        private final int wireValue;
        private final boolean exposureStateCertified;

        Outcome(int wireValue, boolean exposureStateCertified) {
            this.wireValue = wireValue;
            this.exposureStateCertified = exposureStateCertified;
        }

        public int getWireValue() { return wireValue; }
        public boolean isExposureStateCertified() { return exposureStateCertified; }

        private static Outcome fromWire(int value) throws ProtocolException {
            for (Outcome outcome : values()) {
                if (outcome.wireValue == value) return outcome;
            }
            throw new ProtocolException("Unknown TERMINAL outcome " + value);
        }
    }

    public static final class CaptureMetadata {
        private final int shutterValue;
        private final int apertureValue;
        private final int isoValue;
        private final int focalLengthBits;
        private final String lensName;

        private CaptureMetadata(
                int shutterValue,
                int apertureValue,
                int isoValue,
                int focalLengthBits,
                String lensName) {
            this.shutterValue = shutterValue;
            this.apertureValue = apertureValue;
            this.isoValue = isoValue;
            this.focalLengthBits = focalLengthBits;
            this.lensName = lensName;
        }

        public int getShutterValue() { return shutterValue; }
        public int getApertureValue() { return apertureValue; }
        public int getIsoValue() { return isoValue; }
        public boolean hasFocalLength() { return focalLengthBits != 0; }
        public int getFocalLengthBits() { return focalLengthBits; }
        public float getFocalLengthMm() { return Float.intBitsToFloat(focalLengthBits); }
        public boolean hasLensName() { return !lensName.isEmpty(); }
        public String getLensName() { return lensName; }
    }

    public abstract static class ControlIndication {
        private ControlIndication() {}
    }

    public static final class PeakBatch extends ControlIndication {
        private final List<Peak> peaks;

        private PeakBatch(List<Peak> peaks) {
            this.peaks = Collections.unmodifiableList(new ArrayList<>(peaks));
        }

        public List<Peak> getPeaks() { return peaks; }
    }

    public static final class Terminal extends ControlIndication {
        private final Outcome outcome;
        private final int total;
        private final CaptureMetadata metadata;

        private Terminal(Outcome outcome, int total, CaptureMetadata metadata) {
            this.outcome = outcome;
            this.total = total;
            this.metadata = metadata;
        }

        public Outcome getOutcome() { return outcome; }
        public int getTotal() { return total; }
        public CaptureMetadata getMetadata() { return metadata; }
        public boolean isSuccess() { return outcome == Outcome.OK; }
        public boolean isExposureStateCertified() {
            return outcome.isExposureStateCertified();
        }
    }

    public static final class Peak {
        private final int x;
        private final int y;
        private final long flux;

        private Peak(int x, int y, long flux) {
            this.x = x;
            this.y = y;
            this.flux = flux;
        }

        public int getX() { return x; }
        public int getY() { return y; }
        public long getFlux() { return flux; }
    }

    public static final class ProtocolException extends Exception {
        private static final long serialVersionUID = 1L;

        public ProtocolException(String message) {
            super(message);
        }
    }
}
