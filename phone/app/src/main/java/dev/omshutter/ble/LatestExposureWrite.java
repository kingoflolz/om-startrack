package dev.omshutter.ble;

import dev.omshutter.protocol.ExposureCatalogs;

/**
 * Latest-wins intent for one serialized camera exposure characteristic.
 *
 * <p>The active write is never replaced or retried. While that write has a known pending
 * outcome, later explicit selections may replace only the queued desired value. A transport or
 * protocol failure resets the desired value to the last authoritative value and therefore drops
 * every queued mutation.</p>
 */
final class LatestExposureWrite {
    enum Offer {
        SATISFIED,
        START_REQUIRED,
        COALESCED,
        STALE_GENERATION
    }

    private ExposureCatalogs.Selection authoritative;
    private ExposureCatalogs.Selection desired;
    private ExposureCatalogs.Selection active;
    private int connectionEpoch = -1;
    private long catalogGeneration = -1L;

    void reset(
            ExposureCatalogs.Selection current,
            int nextConnectionEpoch,
            long nextCatalogGeneration) {
        authoritative = current;
        desired = current;
        active = null;
        connectionEpoch = nextConnectionEpoch;
        catalogGeneration = nextCatalogGeneration;
    }

    void clear() {
        authoritative = null;
        desired = null;
        active = null;
        connectionEpoch = -1;
        catalogGeneration = -1L;
    }

    Offer offer(
            ExposureCatalogs.Selection selection,
            int expectedConnectionEpoch,
            long expectedCatalogGeneration) {
        if (selection == null) throw new IllegalArgumentException("selection is null");
        if (!isCurrent(expectedConnectionEpoch, expectedCatalogGeneration)) {
            return Offer.STALE_GENERATION;
        }
        desired = selection;
        if (active != null) return Offer.COALESCED;
        return selection.equals(authoritative) ? Offer.SATISFIED : Offer.START_REQUIRED;
    }

    ExposureCatalogs.Selection startNext(
            int expectedConnectionEpoch,
            long expectedCatalogGeneration) {
        if (!isCurrent(expectedConnectionEpoch, expectedCatalogGeneration)
                || active != null
                || desired == null
                || desired.equals(authoritative)) {
            return null;
        }
        active = desired;
        return active;
    }

    boolean confirmActive(
            int expectedConnectionEpoch,
            long expectedCatalogGeneration) {
        if (!isCurrent(expectedConnectionEpoch, expectedCatalogGeneration) || active == null) {
            return false;
        }
        authoritative = active;
        active = null;
        return true;
    }

    /** Updates the observed field of a full-state write without discarding a queued UI intent. */
    boolean observeAuthoritative(
            ExposureCatalogs.Selection current,
            int expectedConnectionEpoch,
            long expectedCatalogGeneration) {
        if (!isCurrent(expectedConnectionEpoch, expectedCatalogGeneration)
                || current == null
                || active != null) {
            return false;
        }
        authoritative = current;
        if (desired == null) desired = current;
        return true;
    }

    /** Drops the active write and every superseding intent without replaying either one. */
    void failToAuthoritative(
            ExposureCatalogs.Selection current,
            int expectedConnectionEpoch,
            long expectedCatalogGeneration) {
        reset(current, expectedConnectionEpoch, expectedCatalogGeneration);
    }

    boolean isCurrent(int expectedConnectionEpoch, long expectedCatalogGeneration) {
        return connectionEpoch == expectedConnectionEpoch
                && catalogGeneration == expectedCatalogGeneration;
    }

    boolean hasActive() {
        return active != null;
    }

    boolean hasQueuedSupersedingIntent() {
        return active != null && desired != null && !desired.equals(active);
    }

    boolean isPending() {
        return active != null
                || (desired != null && !desired.equals(authoritative));
    }

    boolean isReady() {
        return active == null
                && desired != null
                && desired.equals(authoritative);
    }

    ExposureCatalogs.Selection getAuthoritative() {
        return authoritative;
    }

    ExposureCatalogs.Selection getDesired() {
        return desired;
    }

    ExposureCatalogs.Selection getActive() {
        return active;
    }
}
