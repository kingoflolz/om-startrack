package dev.omshutter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.location.LocationManager;

import org.junit.Test;

public final class ForegroundLocationAcquirerTest {
    @Test
    public void networkIsTheOnlySelectedProviderWhenBothAreEnabled() {
        assertEquals(
                LocationManager.NETWORK_PROVIDER,
                ForegroundLocationAcquirer.chooseProvider(true, true));
    }

    @Test
    public void gpsIsSelectedOnlyWhenNetworkIsUnavailable() {
        assertEquals(
                LocationManager.GPS_PROVIDER,
                ForegroundLocationAcquirer.chooseProvider(false, true));
    }

    @Test
    public void noProviderIsSelectedWhenNeitherIsEnabled() {
        assertNull(ForegroundLocationAcquirer.chooseProvider(false, false));
    }
}
