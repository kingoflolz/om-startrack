package dev.omshutter.protocol;

import java.util.UUID;

/** Optional temporary pre-position command, independent of the renewable RATE value. */
public final class IbisPrepositionProtocol {
    public static final UUID CHARACTERISTIC_UUID =
            UUID.fromString("7e6d7a50-2c56-4f17-9c51-9a5f64b02442");
    public static final int VALUE_BYTES = 8;
    public static final int ACTION_STAGE_STAR = 1;
    public static final int ACTION_APPLY_AFTER_HALF_PRESS = 2;
    public static final int MAX_PLANAR_COUNTS = 2_546;
    public static final int MAX_ROLL_COUNTS = 5_325;
    /** Fixed experimental delay; completion of the write does not measure stage settlement. */
    public static final long SETTLE_MS = 750L;

    private IbisPrepositionProtocol() {}

    public static byte[] arm(int x, int y, int roll, int action) {
        if (action != ACTION_STAGE_STAR && action != ACTION_APPLY_AFTER_HALF_PRESS) {
            throw new IllegalArgumentException("Invalid pre-position action");
        }
        // Check components before absolute values, products, and squares so even arbitrary
        // int callers cannot overflow validation. This is app travel policy; firmware checks
        // native representation and retains stock profile and combined-target limits.
        if (x < -MAX_PLANAR_COUNTS || x > MAX_PLANAR_COUNTS
                || y < -MAX_PLANAR_COUNTS || y > MAX_PLANAR_COUNTS
                || roll < -MAX_ROLL_COUNTS || roll > MAX_ROLL_COUNTS) {
            throw new IllegalArgumentException("Pre-position exceeds the app capture range");
        }
        int absoluteRoll = Math.abs(roll);
        if (x * x + y * y > MAX_PLANAR_COUNTS * MAX_PLANAR_COUNTS
                || Math.abs(x) > 2_700 - absoluteRoll * 1_628 / 32_768
                || Math.abs(y) > 2_700 - absoluteRoll * 1_571 / 32_768) {
            throw new IllegalArgumentException("Pre-position exceeds the coupled capture envelope");
        }
        byte[] value = new byte[VALUE_BYTES];
        putSigned16(value, 0, x);
        putSigned16(value, 2, y);
        putSigned16(value, 4, roll);
        value[6] = (byte) action;
        return value;
    }

    public static byte[] cancel() { return new byte[VALUE_BYTES]; }

    private static void putSigned16(byte[] value, int offset, int field) {
        if (field < Short.MIN_VALUE || field > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Pre-position count does not fit int16");
        }
        value[offset] = (byte) field;
        value[offset + 1] = (byte) (field >>> 8);
    }
}
