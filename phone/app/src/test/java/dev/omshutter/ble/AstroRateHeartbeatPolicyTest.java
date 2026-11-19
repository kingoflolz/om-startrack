package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AstroRateHeartbeatPolicyTest {
    @Test
    public void stockPrepositionKeepsExactRenewalsUntilFullPressCompletes() {
        IbisPrepositionCapture capture = new IbisPrepositionCapture();
        assertTrue(AstroRateHeartbeatPolicy.canAdaptDuringPreposition(
                capture.isPreparing(), false));
        assertTrue(capture.begin());
        assertFalse(AstroRateHeartbeatPolicy.canAdaptDuringPreposition(
                capture.isPreparing(), false));
        assertTrue(capture.halfPressCompleted(true));
        assertFalse(AstroRateHeartbeatPolicy.canAdaptDuringPreposition(
                capture.isPreparing(), false));
        assertTrue(capture.applyCompleted(true));
        assertFalse(AstroRateHeartbeatPolicy.canAdaptDuringPreposition(
                capture.isPreparing(), false));
        assertTrue(capture.settleCompleted());
        assertFalse(AstroRateHeartbeatPolicy.canAdaptDuringPreposition(
                capture.isPreparing(), false));

        int[] adaptiveCalls = {0};
        int[] exactCalls = {0};
        AstroRateHeartbeatPolicy.Attempt adaptive = () -> {
            if (!AstroRateHeartbeatPolicy.canAdaptDuringPreposition(
                    capture.isPreparing(), false)) return false;
            adaptiveCalls[0]++;
            return true;
        };
        AstroRateHeartbeatPolicy.Attempt exact = () -> {
            exactCalls[0]++;
            return true;
        };
        assertTrue(AstroRateHeartbeatPolicy.queueAdaptiveOrExact(adaptive, exact));
        assertEquals(0, adaptiveCalls[0]);
        assertEquals(1, exactCalls[0]);

        assertTrue(capture.fullPressCompleted(true));
        assertTrue(AstroRateHeartbeatPolicy.queueAdaptiveOrExact(adaptive, exact));
        assertEquals(1, adaptiveCalls[0]);
        assertEquals(1, exactCalls[0]);
    }

    @Test
    public void starStageBlocksAdaptationUntilOwnershipTransfersToCamera() {
        assertFalse(AstroRateHeartbeatPolicy.canAdaptDuringPreposition(false, true));
        assertFalse(AstroRateHeartbeatPolicy.canAdaptDuringPreposition(true, true));
        assertTrue(AstroRateHeartbeatPolicy.canAdaptDuringPreposition(false, false));
    }

    @Test
    public void adaptiveReplacementIsTheHeartbeatAndSuppressesExactRenewal() {
        int[] adaptiveCalls = {0};
        int[] exactCalls = {0};

        assertTrue(AstroRateHeartbeatPolicy.queueAdaptiveOrExact(
                () -> {
                    adaptiveCalls[0]++;
                    return true;
                },
                () -> {
                    exactCalls[0]++;
                    return true;
                }));

        assertEquals(1, adaptiveCalls[0]);
        assertEquals(0, exactCalls[0]);
    }

    @Test
    public void unavailableAdaptiveReplacementFallsBackToOneExactRenewal() {
        int[] adaptiveCalls = {0};
        int[] exactCalls = {0};

        assertTrue(AstroRateHeartbeatPolicy.queueAdaptiveOrExact(
                () -> {
                    adaptiveCalls[0]++;
                    return false;
                },
                () -> {
                    exactCalls[0]++;
                    return true;
                }));

        assertEquals(1, adaptiveCalls[0]);
        assertEquals(1, exactCalls[0]);
    }

    @Test
    public void failedFallbackReportsThatNoHeartbeatWasQueued() {
        assertFalse(AstroRateHeartbeatPolicy.queueAdaptiveOrExact(
                () -> false,
                () -> false));
    }
}
