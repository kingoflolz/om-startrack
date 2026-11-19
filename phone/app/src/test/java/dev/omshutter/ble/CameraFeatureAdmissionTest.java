package dev.omshutter.ble;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CameraFeatureAdmissionTest {
    @Test
    public void temporarilyUnavailableMandatoryStarSnapshotDoesNotBlockSafeStockControls() {
        // Connection admission already required complete STAR. Its initial coherent STATE
        // bank may still be loading; stock AF/movie/wake remain independently usable.
        boolean stockControlsReady = CameraFeatureAdmission.canUseStockControls(
                true, true, true);
        assertTrue(stockControlsReady);

        assertFalse(CameraFeatureAdmission.canUseStarSettings(
                stockControlsReady, true, false));
        assertFalse(CameraFeatureAdmission.canUseStarCaptureOrSolve(
                stockControlsReady, true, false));
    }

    @Test
    public void stockControlsStillRequireReadyBaseTransportAndIdlePath() {
        assertFalse(CameraFeatureAdmission.canUseStockControls(false, true, true));
        assertFalse(CameraFeatureAdmission.canUseStockControls(true, false, true));
        assertFalse(CameraFeatureAdmission.canUseStockControls(true, true, false));
    }

    @Test
    public void unresolvedStarRecoveryBlocksEveryNewStockMutation() {
        assertTrue(CameraFeatureAdmission.canStartStockMutation(true, false, false));
        assertFalse(CameraFeatureAdmission.canStartStockMutation(true, true, false));
        assertFalse(CameraFeatureAdmission.canStartStockMutation(true, false, true));
        assertFalse(CameraFeatureAdmission.canStartStockMutation(false, false, false));
    }
}
