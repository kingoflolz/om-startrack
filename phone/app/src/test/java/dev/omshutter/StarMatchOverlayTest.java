package dev.omshutter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class StarMatchOverlayTest {
    @Test
    public void classifiesSourceIndicesAndGivesPatternPrecedence() {
        StarMatchOverlay overlay = StarMatchOverlay.of(
                new int[]{17, 4, 17},
                new int[]{2, 4, 31, 31});

        assertEquals(StarMatchOverlay.Kind.PATTERN, overlay.kindForSourceIndex(17));
        assertEquals(StarMatchOverlay.Kind.PATTERN, overlay.kindForSourceIndex(4));
        assertEquals(StarMatchOverlay.Kind.VALIDATION, overlay.kindForSourceIndex(2));
        assertEquals(StarMatchOverlay.Kind.VALIDATION, overlay.kindForSourceIndex(31));
        assertEquals(StarMatchOverlay.Kind.NONE, overlay.kindForSourceIndex(0));
        assertEquals(2, overlay.getPatternCount());
        assertEquals(2, overlay.getValidationCount());
        assertFalse(overlay.isEmpty());
    }

    @Test
    public void emptyAndNullInputsProduceNoClassification() {
        StarMatchOverlay overlay = StarMatchOverlay.of(null, new int[0]);

        assertTrue(overlay.isEmpty());
        assertEquals(StarMatchOverlay.Kind.NONE, overlay.kindForSourceIndex(-1));
        assertEquals(StarMatchOverlay.Kind.NONE, overlay.kindForSourceIndex(256));
    }

    @Test
    public void rejectsIndicesOutsideCameraSourceDomain() {
        try {
            StarMatchOverlay.of(new int[]{256}, null);
            fail("Expected source-domain rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("0..255"));
        }
    }
}
