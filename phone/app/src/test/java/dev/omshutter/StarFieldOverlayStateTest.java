package dev.omshutter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StarFieldOverlayStateTest {
    @Test
    public void repeatedIdenticalPeakPublicationPreservesSolvedOverlay() {
        StarFieldOverlayState state = new StarFieldOverlayState();
        long[] peakData = {packed(100, 200), 9000, packed(300, 400), 7000};
        state.replacePeakData(peakData);
        state.setMatches(new int[]{0}, new int[]{1});

        state.replacePeakData(peakData.clone());

        assertTrue(state.hasMatches());
        assertEquals(StarMatchOverlay.Kind.PATTERN, state.kindForSourceIndex(0));
        assertEquals(StarMatchOverlay.Kind.VALIDATION, state.kindForSourceIndex(1));
    }

    @Test
    public void replacedOrEmptyPeakDataClearsSolvedOverlay() {
        StarFieldOverlayState state = new StarFieldOverlayState();
        state.replacePeakData(new long[]{packed(100, 200), 9000});
        state.setMatches(new int[]{0}, null);

        state.replacePeakData(new long[]{packed(101, 200), 9000});
        assertFalse(state.hasMatches());

        // A delayed classification cannot survive an already-empty replacement.
        state.replacePeakData(new long[0]);
        state.setMatches(new int[]{0}, null);
        assertFalse(state.hasMatches());
        state.replacePeakData(new long[0]);
        assertFalse(state.hasMatches());
    }

    @Test
    public void onlyAssociationsPresentInCurrentPlotAreExposed() {
        StarFieldOverlayState state = new StarFieldOverlayState();
        state.replacePeakData(new long[]{packed(10, 20), 100});

        state.setMatches(new int[]{7}, new int[]{0, 8});

        assertEquals(0, state.getPatternCount());
        assertEquals(1, state.getValidationCount());
        assertEquals(StarMatchOverlay.Kind.VALIDATION, state.kindForSourceIndex(0));
        assertEquals(StarMatchOverlay.Kind.NONE, state.kindForSourceIndex(7));
    }

    private static long packed(int x, int y) {
        return ((long) x << 16) | y;
    }
}
