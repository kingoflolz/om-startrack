package dev.omshutter.protocol;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/** Pure encoder/decoder for the proprietary Olympus/OM System BLE envelope. */
public final class OlympusProtocol {
    public static final UUID SERVICE_UUID =
            UUID.fromString("adc505f9-4e58-4b71-b8ca-983bb8c73e4f");
    public static final UUID COMMAND_UUID =
            UUID.fromString("82f949b4-f5dc-4cf3-ab3c-fd9fd4017b68");
    public static final UUID RESULT_UUID =
            UUID.fromString("b7a8015c-cb94-4efa-bda2-b7921fa9951f");
    public static final UUID EVENT_UUID =
            UUID.fromString("05a02050-0860-4919-8add-9801fba8b6ed");

    public static final int TYPE_REQUEST = 0x01;
    public static final int TYPE_ACK = 0x02;
    public static final int TYPE_NACK = 0x03;
    public static final int TYPE_RESULT = 0x04;
    public static final int TYPE_RECEIPT = 0x05;

    public static final int ACTION_HALF_PRESS = 0x01;
    public static final int ACTION_HALF_RELEASE = 0x02;
    public static final int ACTION_FULL_PRESS_OVER_HALF = 0x03;
    public static final int ACTION_FULL_RELEASE_TO_HALF = 0x04;
    public static final int ACTION_COMBINED_PRESS = 0x05;
    public static final int ACTION_COMBINED_RELEASE = 0x06;

    private OlympusProtocol() {}

    public static byte[] request(int sequence, int group, int opcode, byte[] parameters) {
        checkByte("sequence", sequence);
        checkByte("group", group);
        checkByte("opcode", opcode);
        byte[] params = parameters == null ? new byte[0] : parameters;
        int bodyLength = params.length + 3;
        if (bodyLength > 0xff) {
            throw new IllegalArgumentException("Protocol body exceeds 255 bytes");
        }

        byte[] frame = new byte[bodyLength + 5];
        frame[0] = (byte) TYPE_REQUEST;
        frame[1] = (byte) sequence;
        frame[2] = (byte) bodyLength;
        frame[3] = (byte) group;
        frame[4] = 0x01;
        frame[5] = (byte) opcode;
        System.arraycopy(params, 0, frame, 6, params.length);
        frame[frame.length - 2] = (byte) checksum(frame, 3, bodyLength);
        frame[frame.length - 1] = 0x00;
        return frame;
    }

    public static byte[] authenticate(int sequence, String passcode) {
        return StockRequestCodec.authenticate(sequence, passcode);
    }

    public static byte[] enterRemoteMode(int sequence) {
        return StockRequestCodec.enterRemoteMode(sequence);
    }

    public static byte[] powerOn(int sequence) {
        return StockRequestCodec.powerOn(sequence);
    }

    public static byte[] shutter(int sequence, int action) {
        return StockRequestCodec.shutter(sequence, action);
    }

    public static byte[] ack(int resultToken) {
        checkByte("result token", resultToken);
        return new byte[]{(byte) TYPE_ACK, (byte) resultToken, 0x00, 0x00, 0x00};
    }

    public static byte[] nack(int resultToken) {
        checkByte("result token", resultToken);
        return new byte[]{(byte) TYPE_NACK, (byte) resultToken, 0x00, 0x00, 0x00};
    }

    public static Frame parse(byte[] value) throws ProtocolException {
        if (value == null || value.length < 5) {
            throw new ProtocolException("Frame is shorter than the five-byte envelope");
        }
        int bodyLength = unsigned(value[2]);
        int expectedLength = bodyLength + 5;
        if (value.length != expectedLength) {
            throw new ProtocolException(
                    "Length mismatch: declared " + bodyLength + " body bytes, got " + value.length);
        }
        if (value[value.length - 1] != 0x00) {
            throw new ProtocolException("Missing zero terminator");
        }
        int expectedChecksum = unsigned(value[value.length - 2]);
        int actualChecksum = checksum(value, 3, bodyLength);
        if (expectedChecksum != actualChecksum) {
            throw new ProtocolException(String.format(
                    Locale.US, "Checksum mismatch: expected %02x, calculated %02x",
                    expectedChecksum, actualChecksum));
        }
        return new Frame(
                unsigned(value[0]),
                unsigned(value[1]),
                Arrays.copyOfRange(value, 3, 3 + bodyLength));
    }

    public static ResultFrame parseResult(byte[] value) throws ProtocolException {
        Frame frame = parse(value);
        if (frame.type != TYPE_RESULT) {
            throw new ProtocolException("Expected result type 04, got " + frame.type);
        }
        if (frame.body.length < 4) {
            throw new ProtocolException("Result body is shorter than four bytes");
        }
        if (unsigned(frame.body[1]) != 0x01) {
            throw new ProtocolException("Invalid result marker");
        }
        if (frame.body.length != 4) {
            throw new ProtocolException("Legacy result body must be four bytes");
        }
        return new ResultFrame(
                frame.token,
                unsigned(frame.body[0]),
                unsigned(frame.body[2]),
                unsigned(frame.body[3]),
                Arrays.copyOfRange(frame.body, 4, frame.body.length));
    }

    public static boolean isReceiptFor(byte[] value, int requestSequence) {
        try {
            Frame frame = parse(value);
            return frame.type == TYPE_RECEIPT
                    && frame.token == (requestSequence & 0xff)
                    && frame.body.length == 0;
        } catch (ProtocolException ignored) {
            return false;
        }
    }

    public static int tokenIfPresent(byte[] value) {
        return value != null && value.length >= 2 ? unsigned(value[1]) : -1;
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format(Locale.US, "%02X", unsigned(bytes[i])));
        }
        return out.toString();
    }

    private static int checksum(byte[] bytes, int offset, int length) {
        int sum = 0;
        for (int i = offset; i < offset + length; i++) {
            sum = (sum + unsigned(bytes[i])) & 0xff;
        }
        return sum;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static void checkByte(String name, int value) {
        if (value < 0 || value > 0xff) {
            throw new IllegalArgumentException(name + " must fit in one byte");
        }
    }

    public static class Frame {
        private final int type;
        private final int token;
        private final byte[] body;

        private Frame(int type, int token, byte[] body) {
            this.type = type;
            this.token = token;
            this.body = body;
        }

        public int getType() { return type; }
        public int getToken() { return token; }
        public byte[] getBody() { return body.clone(); }
    }

    public static final class ResultFrame {
        private final int token;
        private final int group;
        private final int opcode;
        private final int status;
        private final byte[] payload;

        private ResultFrame(int token, int group, int opcode, int status, byte[] payload) {
            this.token = token;
            this.group = group;
            this.opcode = opcode;
            this.status = status;
            this.payload = payload == null ? new byte[0] : payload.clone();
        }

        public int getToken() { return token; }
        public int getGroup() { return group; }
        public int getOpcode() { return opcode; }
        public int getStatus() { return status; }
        public byte[] getPayload() { return payload.clone(); }
    }

    public static final class ProtocolException extends Exception {
        public ProtocolException(String message) {
            super(message);
        }
    }
}
