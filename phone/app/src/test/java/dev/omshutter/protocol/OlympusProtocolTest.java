package dev.omshutter.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Regression coverage for the stock Olympus command/result envelope. */
public final class OlympusProtocolTest {
    @Test
    public void buildsStockFrames() {
        assertHex(
                "01 01 09 0C 01 02 31 32 33 34 35 36 44 00",
                OlympusProtocol.authenticate(0x01, "123456"));
        assertHex("01 02 04 1D 01 01 01 20 00", OlympusProtocol.enterRemoteMode(0x02));
        assertHex("01 02 04 0F 01 01 02 13 00", OlympusProtocol.powerOn(0x02));
        assertHex("01 03 04 04 01 0F 01 15 00", OlympusProtocol.shutter(0x03, 1));
    }

    @Test
    public void parsesStockProtocolResult() throws Exception {
        OlympusProtocol.ResultFrame parsed = OlympusProtocol.parseResult(
                hex("04 06 04 04 01 0F 00 14 00"));
        assertEquals(0x06, parsed.getToken());
        assertEquals(0x04, parsed.getGroup());
        assertEquals(0x0f, parsed.getOpcode());
        assertEquals(0, parsed.getStatus());
        assertArrayEquals(new byte[0], parsed.getPayload());
    }

    private static void assertHex(String expected, byte[] actual) {
        assertArrayEquals(hex(expected), actual);
    }

    private static byte[] hex(String text) {
        String compact = text.replace(" ", "");
        byte[] output = new byte[compact.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(
                    compact.substring(index * 2, index * 2 + 2), 16);
        }
        return output;
    }
}
