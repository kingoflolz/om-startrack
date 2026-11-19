package dev.omshutter.protocol;

import static org.junit.Assert.*;
import org.junit.Test;

public final class IbisPrepositionProtocolTest {
    @Test public void wireIsExactLittleEndianTupleAndIndependentOfRate() {
        assertArrayEquals(new byte[]{0x08, 0x07, (byte) 0xf8, (byte) 0xf8,
                0x33, (byte) 0xeb, 2, 0}, IbisPrepositionProtocol.arm(
                        1800, -1800, -5325,
                        IbisPrepositionProtocol.ACTION_APPLY_AFTER_HALF_PRESS));
        assertEquals(18, AstroRateGattProtocol.VALUE_BYTES);
        assertArrayEquals(new byte[8], IbisPrepositionProtocol.cancel());
    }

    @Test public void expandedCaptureLimitsAreInclusiveOnEveryAxis() {
        for (int action : new int[]{1, 2}) {
            for (int sign : new int[]{-1, 1}) {
                assertEquals(8, IbisPrepositionProtocol.arm(
                        sign * 1800, -sign * 1800, sign * 5325, action).length);
                assertEquals(8, IbisPrepositionProtocol.arm(sign * 2546, 0, 0, action).length);
                assertEquals(8, IbisPrepositionProtocol.arm(0, sign * 2546, 0, action).length);
            }
        }
    }

    @Test public void circleAndDifferentRollCouplingsHoldForAllSigns() {
        for (int action : new int[]{1, 2}) {
            for (int xSign : new int[]{-1, 1}) {
                for (int ySign : new int[]{-1, 1}) {
                    for (int rollSign : new int[]{-1, 1}) {
                        int roll = rollSign * 5325;
                        assertEquals(8, IbisPrepositionProtocol.arm(
                                xSign * 2436, 0, roll, action).length);
                        assertEquals(8, IbisPrepositionProtocol.arm(
                                0, ySign * 2445, roll, action).length);
                        assertThrows(IllegalArgumentException.class,
                                () -> IbisPrepositionProtocol.arm(xSign * 2437, 0, roll, action));
                        assertThrows(IllegalArgumentException.class,
                                () -> IbisPrepositionProtocol.arm(0, ySign * 2446, roll, action));
                        assertEquals(8, IbisPrepositionProtocol.arm(
                                xSign * 1800, ySign * 1800, roll, action).length);
                        assertThrows(IllegalArgumentException.class,
                                () -> IbisPrepositionProtocol.arm(
                                        xSign * 1801, ySign * 1801, roll, action));
                    }
                }
            }
        }
    }

    @Test public void circleBoundaryRejectsAnExtraCountOnTheOtherAxis() {
        for (int sign : new int[]{-1, 1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> IbisPrepositionProtocol.arm(sign * 2546, 1, 0, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> IbisPrepositionProtocol.arm(-1, sign * 2546, 0, 2));
        }
    }

    @Test public void valuesBeyondComponentsAreRejectedBeforeArithmetic() {
        for (int sign : new int[]{-1, 1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> IbisPrepositionProtocol.arm(sign * 2547, 0, 0, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> IbisPrepositionProtocol.arm(0, sign * 2547, 0, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> IbisPrepositionProtocol.arm(0, 0, sign * 5326, 2));
            assertThrows(IllegalArgumentException.class,
                    () -> IbisPrepositionProtocol.arm(0, 0, sign * 21299, 2));
        }
        for (int extreme : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, -32768, 32767}) {
            assertThrows(IllegalArgumentException.class,
                    () -> IbisPrepositionProtocol.arm(extreme, extreme, extreme, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> IbisPrepositionProtocol.arm(0, extreme, 0, 2));
            assertThrows(IllegalArgumentException.class,
                    () -> IbisPrepositionProtocol.arm(0, 0, extreme, 1));
        }
    }

    @Test public void invalidActionsAndTruncatedCountsAreRejected() {
        for (int action : new int[]{-1, 0, 3, 65537}) {
            assertThrows(IllegalArgumentException.class,
                    () -> IbisPrepositionProtocol.arm(0, 0, 0, action));
        }
        assertThrows(IllegalArgumentException.class,
                () -> IbisPrepositionProtocol.arm(32768, 0, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> IbisPrepositionProtocol.arm(0, -32769, 0, 1));
    }
}
