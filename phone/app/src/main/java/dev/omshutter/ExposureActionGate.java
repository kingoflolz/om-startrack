package dev.omshutter;

import dev.omshutter.protocol.ExposureCatalogs;
import dev.omshutter.protocol.ExposureDraft;
import dev.omshutter.protocol.CameraExposureState;

/** Pure fail-closed gates for live shutter settings and the camera-owned STAR recipe. */
final class ExposureActionGate {
    private ExposureActionGate() {}

    /** The stock BLE shutter remains independent while mandatory STAR STATE initializes. */
    static boolean isStockShutterReady(
            boolean starSettingsAvailable,
            boolean synchronizedStarSettingsReady,
            boolean stockMutationAllowed) {
        return stockMutationAllowed
                && (!starSettingsAvailable || synchronizedStarSettingsReady);
    }

    static boolean isShutterReady(
            ExposureDraft liveDraft,
            CameraExposureState authoritativeState,
            ExposureCatalogs.Selection desiredLiveSelection,
            boolean liveRequestAwaitingService,
            boolean recipeRequestAwaitingService,
            boolean liveWritePending,
            boolean recipeWritePending,
            boolean serviceReady) {
        if (liveRequestAwaitingService
                || recipeRequestAwaitingService
                || liveWritePending
                || recipeWritePending
                || !serviceReady
                || authoritativeState == null
                || !matchesDraft(liveDraft, desiredLiveSelection)) {
            return false;
        }
        return desiredLiveSelection.matches(authoritativeState);
    }

    static boolean isDetectReady(
            ExposureDraft liveDraft,
            ExposureDraft recipeDraft,
            CameraExposureState authoritativeState,
            ExposureCatalogs.Selection desiredLiveSelection,
            ExposureCatalogs.Selection committedRecipeSelection,
            ExposureCatalogs.Selection desiredRecipeSelection,
            boolean liveRequestAwaitingService,
            boolean recipeRequestAwaitingService,
            boolean liveWritePending,
            boolean recipeWritePending,
            boolean serviceReadyForShutter,
            boolean serviceReadyForCapture) {
        return serviceReadyForCapture
                && isShutterReady(
                        liveDraft,
                        authoritativeState,
                        desiredLiveSelection,
                        liveRequestAwaitingService,
                        recipeRequestAwaitingService,
                        liveWritePending,
                        recipeWritePending,
                        serviceReadyForShutter)
                && matchesDraft(recipeDraft, desiredRecipeSelection)
                && desiredRecipeSelection.equals(committedRecipeSelection);
    }

    private static boolean matchesDraft(
            ExposureDraft draft, ExposureCatalogs.Selection selection) {
        return draft != null
                && selection != null
                && draft.isSynchronized()
                && draft.getSelection().equals(selection);
    }
}
