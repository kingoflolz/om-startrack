package dev.omshutter.ble;

/** Pure scheduling policy for the coherent STAR STATE/catalog initialization fence. */
final class StarCatalogRefreshPolicy {
    enum NotificationDecision {
        /** The in-flight STATE-A/catalogs/STATE-B owner decides this generation. */
        IGNORE_IN_FLIGHT,
        /** This STATE has no bank authority; wait for a later available notification. */
        WAIT_FOR_AVAILABLE,
        /** Read a new coherent catalog bank before resolving the observed indices. */
        REFRESH,
        /** The installed bank already resolves the observed STATE generation. */
        KEEP_INSTALLED
    }

    private StarCatalogRefreshPolicy() {}

    /**
     * Classifies one parsed STATE notification without scheduling work itself.
     *
     * <p>An unavailable STATE is not a catalog-generation change. In particular, repeated partial
     * notifications after startup retry exhaustion must remain idle until the camera publishes a
     * nonzero available generation. That first available notification then requests one fresh
     * fence.</p>
     */
    static NotificationDecision classifyNotification(
            boolean initializationReadBatch,
            boolean catalogBankInstalled,
            long installedGeneration,
            boolean observedCatalogAvailable,
            long observedGeneration) {
        if (initializationReadBatch) {
            return NotificationDecision.IGNORE_IN_FLIGHT;
        }
        if (!observedCatalogAvailable || observedGeneration == 0L) {
            return NotificationDecision.WAIT_FOR_AVAILABLE;
        }
        if (!catalogBankInstalled || installedGeneration != observedGeneration) {
            return NotificationDecision.REFRESH;
        }
        return NotificationDecision.KEEP_INSTALLED;
    }

    /**
     * A STATE notification received while the initial catalog fence is running is telemetry,
     * not a request to replace that in-flight fence. The final STATE-B read will either install
     * the coherent bank or make the batch retry through its bounded read-retry owner.
     */
    static boolean initializationOwnsMissingCatalogRefresh(
            boolean catalogsMissing, boolean initializationReadBatch) {
        return catalogsMissing && initializationReadBatch;
    }

    /** Deferred refreshes may only replace an idle read owner. */
    static boolean canStartDeferredRefresh(
            boolean refreshRequired,
            boolean initializationReadBatch,
            boolean readQueueEmpty) {
        return refreshRequired && !initializationReadBatch && readQueueEmpty;
    }
}
