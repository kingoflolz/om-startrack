package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.omshutter.protocol.ExposureCatalogs;

import org.junit.Test;

public final class LatestExposureWriteTest {
    private static final int EPOCH = 7;
    private static final long CATALOG = 11L;
    private static final ExposureCatalogs.Selection BASELINE = selection("1/60");
    private static final ExposureCatalogs.Selection A = selection("1/125");
    private static final ExposureCatalogs.Selection B = selection("1/250");
    private static final ExposureCatalogs.Selection C = selection("1/500");

    @Test
    public void initialAuthoritativeSelectionIsReadyWithoutAWrite() {
        LatestExposureWrite writes = new LatestExposureWrite();
        writes.reset(BASELINE, EPOCH, CATALOG);

        assertTrue(writes.isReady());
        assertFalse(writes.isPending());
        assertSame(BASELINE, writes.getDesired());
        assertEquals(
                LatestExposureWrite.Offer.SATISFIED,
                writes.offer(BASELINE, EPOCH, CATALOG));
        assertNull(writes.startNext(EPOCH, CATALOG));
    }

    @Test
    public void rapidChangesCoalesceBehindOneActiveWrite() {
        LatestExposureWrite writes = new LatestExposureWrite();
        writes.reset(BASELINE, EPOCH, CATALOG);

        assertEquals(
                LatestExposureWrite.Offer.START_REQUIRED,
                writes.offer(A, EPOCH, CATALOG));
        assertSame(A, writes.startNext(EPOCH, CATALOG));
        assertEquals(
                LatestExposureWrite.Offer.COALESCED,
                writes.offer(B, EPOCH, CATALOG));
        assertEquals(
                LatestExposureWrite.Offer.COALESCED,
                writes.offer(C, EPOCH, CATALOG));
        assertTrue(writes.hasQueuedSupersedingIntent());
        assertSame(A, writes.getActive());
        assertSame(C, writes.getDesired());

        assertTrue(writes.confirmActive(EPOCH, CATALOG));
        assertSame(C, writes.startNext(EPOCH, CATALOG));
        assertTrue(writes.confirmActive(EPOCH, CATALOG));
        assertTrue(writes.isReady());
        assertSame(C, writes.getAuthoritative());
    }

    @Test
    public void selectingTheActiveValueDeduplicatesQueuedWrite() {
        LatestExposureWrite writes = new LatestExposureWrite();
        writes.reset(BASELINE, EPOCH, CATALOG);
        writes.offer(A, EPOCH, CATALOG);
        writes.startNext(EPOCH, CATALOG);
        writes.offer(B, EPOCH, CATALOG);
        writes.offer(A, EPOCH, CATALOG);

        assertFalse(writes.hasQueuedSupersedingIntent());
        assertTrue(writes.confirmActive(EPOCH, CATALOG));
        assertNull(writes.startNext(EPOCH, CATALOG));
        assertTrue(writes.isReady());
    }

    @Test
    public void failureDropsActiveAndSupersedingMutations() {
        LatestExposureWrite writes = new LatestExposureWrite();
        writes.reset(BASELINE, EPOCH, CATALOG);
        writes.offer(A, EPOCH, CATALOG);
        writes.startNext(EPOCH, CATALOG);
        writes.offer(B, EPOCH, CATALOG);

        writes.failToAuthoritative(BASELINE, EPOCH, CATALOG);

        assertFalse(writes.isPending());
        assertTrue(writes.isReady());
        assertSame(BASELINE, writes.getDesired());
        assertNull(writes.startNext(EPOCH, CATALOG));
    }

    @Test
    public void fullStateObservationPreservesQueuedIntentForAnotherField() {
        LatestExposureWrite writes = new LatestExposureWrite();
        writes.reset(BASELINE, EPOCH, CATALOG);
        assertEquals(
                LatestExposureWrite.Offer.START_REQUIRED,
                writes.offer(B, EPOCH, CATALOG));

        assertTrue(writes.observeAuthoritative(A, EPOCH, CATALOG));
        assertSame(A, writes.getAuthoritative());
        assertSame(B, writes.getDesired());
        assertSame(B, writes.startNext(EPOCH, CATALOG));
    }

    @Test
    public void reconnectOrCatalogReplacementInvalidatesQueuedIntent() {
        LatestExposureWrite writes = new LatestExposureWrite();
        writes.reset(BASELINE, EPOCH, CATALOG);
        writes.offer(A, EPOCH, CATALOG);
        writes.startNext(EPOCH, CATALOG);
        writes.offer(B, EPOCH, CATALOG);

        writes.reset(C, EPOCH + 1, CATALOG + 1);

        assertEquals(
                LatestExposureWrite.Offer.STALE_GENERATION,
                writes.offer(B, EPOCH, CATALOG));
        assertNull(writes.startNext(EPOCH, CATALOG));
        assertTrue(writes.isReady());
        assertSame(C, writes.getDesired());
    }

    private static ExposureCatalogs.Selection selection(String shutter) {
        return new ExposureCatalogs.Selection(shutter, "F2.8", "800");
    }
}
