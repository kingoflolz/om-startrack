package dev.omshutter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class FocalLengthDisplayTest {
    @Test
    public void alwaysRoundsToExactlyTwoDecimalPlaces() {
        assertEquals("24.00", FocalLengthDisplay.format(24.0f));
        assertEquals("48.22", FocalLengthDisplay.format(48.22265625f));
        assertEquals("12.35", FocalLengthDisplay.format(12.345f));
    }
}
