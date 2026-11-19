package dev.omshutter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import dev.omshutter.protocol.ExposureCatalogs;
import dev.omshutter.protocol.StarCaptureProtocol;
import dev.omshutter.protocol.ExposureFixture;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class CapturedExposureDisplayTest {
    @Test
    public void usesCaptureBoundRecipeWhenRestoredLiveStateDiffers() throws Exception {
        ExposureCatalogs.Selection capturedRecipe =
                new ExposureCatalogs.Selection("4\"", "F4.0", "3200");
        ExposureFixture restoredLiveState = state("1/125", "F8.0", "200");

        String captured = CapturedExposureDisplay.format(capturedRecipe);
        String restored = CapturedExposureDisplay.format(
                ExposureCatalogs.Selection.from(restoredLiveState));

        assertEquals("F4.0 · 4s · ISO 3200", captured);
        assertNotEquals(restored, captured);
    }

    private static ExposureFixture state(
            String shutter, String aperture, String iso) throws Exception {
        return new ExposureFixture(shutter, aperture, iso, aperture, aperture);
    }

    private static void writeLabel(ByteArrayOutputStream output, String label) {
        byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
        output.write(bytes.length);
        output.write(bytes, 0, bytes.length);
    }

    private static void writeHalfword(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void writeWord(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }
}
