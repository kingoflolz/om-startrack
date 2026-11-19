package dev.omshutter.protocol;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MeasurementFormatTest {
    @Test
    public void preservesUnroundedFloatFocalAndExactQ8Values() {
        assertEquals(
                "22.867944717407227",
                MeasurementFormat.focalLength(Float.intBitsToFloat(0x41b6f18d)));
        assertEquals("48.22265625", MeasurementFormat.unsignedQ8(12345));
    }
}
