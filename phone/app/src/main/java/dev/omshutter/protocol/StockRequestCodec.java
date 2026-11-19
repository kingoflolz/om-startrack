package dev.omshutter.protocol;

import java.nio.charset.StandardCharsets;

/** Pure request encoder for the stock Olympus BLE command surface used by the app. */
final class StockRequestCodec {
    private StockRequestCodec() {}

    static byte[] authenticate(int sequence, String passcode) {
        if (passcode == null || passcode.isEmpty()) {
            throw new IllegalArgumentException("Passcode is empty");
        }
        return OlympusProtocol.request(
                sequence, 0x0c, 0x02, passcode.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] enterRemoteMode(int sequence) {
        return OlympusProtocol.request(sequence, 0x1d, 0x01, new byte[]{0x01});
    }

    static byte[] powerOn(int sequence) {
        return OlympusProtocol.request(sequence, 0x0f, 0x01, new byte[]{0x02});
    }

    static byte[] shutter(int sequence, int action) {
        if (action < OlympusProtocol.ACTION_HALF_PRESS
                || action > OlympusProtocol.ACTION_COMBINED_RELEASE) {
            throw new IllegalArgumentException("Unknown shutter action: " + action);
        }
        return OlympusProtocol.request(sequence, 0x04, 0x0f, new byte[]{(byte) action});
    }
}
