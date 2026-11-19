package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StarCatalogRefreshPolicyTest {
    @Test
    public void partialNotificationWaitsWithoutOpeningAnotherFence() {
        assertEquals(
                StarCatalogRefreshPolicy.NotificationDecision.WAIT_FOR_AVAILABLE,
                StarCatalogRefreshPolicy.classifyNotification(
                        false, false, 0L, false, 0L));

        // Losing authority also waits instead of treating canonical zero as a new generation.
        assertEquals(
                StarCatalogRefreshPolicy.NotificationDecision.WAIT_FOR_AVAILABLE,
                StarCatalogRefreshPolicy.classifyNotification(
                        false, true, 7L, false, 0L));
    }

    @Test
    public void firstAvailableOrChangedGenerationRequestsOneFreshFence() {
        assertEquals(
                StarCatalogRefreshPolicy.NotificationDecision.REFRESH,
                StarCatalogRefreshPolicy.classifyNotification(
                        false, false, 0L, true, 7L));
        assertEquals(
                StarCatalogRefreshPolicy.NotificationDecision.REFRESH,
                StarCatalogRefreshPolicy.classifyNotification(
                        false, true, 7L, true, 8L));
        assertEquals(
                StarCatalogRefreshPolicy.NotificationDecision.KEEP_INSTALLED,
                StarCatalogRefreshPolicy.classifyNotification(
                        false, true, 8L, true, 8L));
    }

    @Test
    public void inFlightFenceOwnsEveryNotificationGeneration() {
        assertEquals(
                StarCatalogRefreshPolicy.NotificationDecision.IGNORE_IN_FLIGHT,
                StarCatalogRefreshPolicy.classifyNotification(
                        true, false, 0L, false, 0L));
        assertEquals(
                StarCatalogRefreshPolicy.NotificationDecision.IGNORE_IN_FLIGHT,
                StarCatalogRefreshPolicy.classifyNotification(
                        true, false, 0L, true, 9L));
    }

    @Test
    public void stateNotificationsInterleavedWithInitializationReadsCannotRestartFence() {
        boolean initializationBatch = true;
        int queuedReads = 5;

        while (queuedReads > 0) {
            // A STAR STATE notification may arrive while any fence read is in flight. Until
            // the coherent bank is installed, that notification is telemetry only: it must not
            // request a replacement initialization batch.
            assertTrue(StarCatalogRefreshPolicy.initializationOwnsMissingCatalogRefresh(
                    true, initializationBatch));

            queuedReads--;
            assertFalse(StarCatalogRefreshPolicy.canStartDeferredRefresh(
                    true, initializationBatch, queuedReads == 0));
        }

        // The final read has been dequeued, but its callback has not yet finished the batch. This
        // queue-empty window was enough to restart the fence before the policy also checked the
        // initialization-batch owner.
        assertFalse(StarCatalogRefreshPolicy.canStartDeferredRefresh(
                true, initializationBatch, true));
    }

    @Test
    public void queuedReadBlocksDeferredRefreshOutsideInitializationBatch() {
        assertFalse(StarCatalogRefreshPolicy.canStartDeferredRefresh(
                true, false, false));
    }

    @Test
    public void requestedRefreshStartsOnceInitializationAndReadQueueAreIdle() {
        assertFalse(StarCatalogRefreshPolicy.initializationOwnsMissingCatalogRefresh(
                true, false));
        assertTrue(StarCatalogRefreshPolicy.canStartDeferredRefresh(
                true, false, true));
        assertFalse(StarCatalogRefreshPolicy.canStartDeferredRefresh(
                false, false, true));
    }
}
