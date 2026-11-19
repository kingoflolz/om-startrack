package dev.omshutter.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.omshutter.protocol.ExposureCatalogs;

import org.junit.Test;

public final class CaptureRecoveryStoreTest {
    private static final class FakeBackend implements CaptureRecoveryStore.Backend {
        String value;
        boolean writesSucceed = true;

        @Override public String read() { return value; }
        @Override public boolean write(String next) {
            if (!writesSucceed) return false;
            value = next;
            return true;
        }
        @Override public boolean clear() {
            if (!writesSucceed) return false;
            value = null;
            return true;
        }
    }

    @Test
    public void exactTupleRoundTripsAsOneCrashSafeRecord() {
        FakeBackend backend = new FakeBackend();
        CaptureRecoveryStore store = new CaptureRecoveryStore(backend);
        CaptureRecoveryStore.Obligation expected = new CaptureRecoveryStore.Obligation(
                "address:AA:BB", new ExposureCatalogs.Selection("1/125", "F4.0", "800"));

        assertTrue(store.arm(expected));
        CaptureRecoveryStore.LoadResult loaded = store.load();
        assertEquals(CaptureRecoveryStore.Status.PENDING, loaded.getStatus());
        assertEquals(expected, loaded.getObligation());
        assertTrue(store.clear());
        assertEquals(CaptureRecoveryStore.Status.EMPTY, store.load().getStatus());
    }

    @Test
    public void malformedOrUnreadableRecordFailsClosed() {
        FakeBackend backend = new FakeBackend();
        CaptureRecoveryStore store = new CaptureRecoveryStore(backend);
        backend.value = "not-a-record";
        CaptureRecoveryStore.LoadResult malformed = store.load();
        assertEquals(CaptureRecoveryStore.Status.CORRUPT, malformed.getStatus());
        assertNull(malformed.getObligation());

        backend.value = "2,YQ,Yg,Yw,ZA";
        assertEquals(CaptureRecoveryStore.Status.CORRUPT, store.load().getStatus());
    }

    @Test
    public void failedDurableWriteNeverClaimsTheBarrierWasArmedOrCleared() {
        FakeBackend backend = new FakeBackend();
        CaptureRecoveryStore store = new CaptureRecoveryStore(backend);
        CaptureRecoveryStore.Obligation expected = new CaptureRecoveryStore.Obligation(
                "camera", new ExposureCatalogs.Selection("1\"", "F2.8", "1600"));
        backend.writesSucceed = false;

        assertFalse(store.arm(expected));
        assertFalse(store.clear());
        assertNull(backend.value);
    }
}
