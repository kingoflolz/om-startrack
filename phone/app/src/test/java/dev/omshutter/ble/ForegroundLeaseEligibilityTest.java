package dev.omshutter.ble;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ForegroundLeaseEligibilityTest {
    @Test
    public void screenOnAtLockscreenDoesNotRenewWithoutVisibleActivity() {
        assertFalse(ForegroundLeaseEligibility.isEligible(false, true, true));
    }

    @Test
    public void freshInteractiveQueryOverridesStaleVisibleAndScreenFlags() {
        assertFalse(ForegroundLeaseEligibility.isEligible(true, true, false));
    }

    @Test
    public void allThreeSignalsMustBeTrue() {
        assertTrue(ForegroundLeaseEligibility.isEligible(true, true, true));
        assertFalse(ForegroundLeaseEligibility.isEligible(true, false, true));
    }
}
