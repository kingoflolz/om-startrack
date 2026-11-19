package dev.omshutter.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import dev.omshutter.solver.PlateSolverSettings;

import org.junit.Test;

public final class PlateSolverSettingsStoreTest {
    private static final class FakeBackend implements PlateSolverSettingsStore.Backend {
        String value;
        RuntimeException readFailure;

        @Override
        public String read() {
            if (readFailure != null) throw readFailure;
            return value;
        }

        @Override
        public void write(String value) {
            this.value = value;
        }

        @Override
        public void clear() {
            value = null;
        }
    }

    @Test
    public void absentSettingsLoadExactDefaults() {
        FakeBackend backend = new FakeBackend();
        PlateSolverSettingsStore store = new PlateSolverSettingsStore(backend);

        assertEquals(PlateSolverSettings.defaults(), store.load());
    }

    @Test
    public void completeConfigurationRoundTripsAsOneVersionedValue() {
        FakeBackend backend = new FakeBackend();
        PlateSolverSettingsStore store = new PlateSolverSettingsStore(backend);
        PlateSolverSettings selected = new PlateSolverSettings(256, 110, 55, 7, 2_500);

        store.save(selected);

        assertEquals("4,256,110,55,7,2500", backend.value);
        assertEquals(selected, store.load());
    }

    @Test
    public void legacyObservationLimitsMigrateTo256AndPreserveOtherKnobs() {
        FakeBackend backend = new FakeBackend();
        PlateSolverSettingsStore store = new PlateSolverSettingsStore(backend);

        for (String legacy : new String[] {
                "1,20,90,75,4,1000",
                "1,128,90,75,4,1000",
                "1,256,90,75,4,1000",
                "2,20,90,75,4,1000",
                "2,128,90,75,4,1000",
                "2,256,90,75,4,1000",
                "3,20,90,75,4,1000",
                "3,128,90,75,4,1000"
        }) {
            backend.value = legacy;
            assertEquals(256, store.load().getMaxObservations());
        }
        for (String legacy : new String[] {
                "1,256,110,55,7,2500",
                "2,256,110,55,7,2500",
                "3,128,110,55,7,2500"
        }) {
            backend.value = legacy;
            assertEquals(new PlateSolverSettings(256, 110, 55, 7, 2_500), store.load());
        }
    }

    @Test
    public void malformedWrongVersionAndOutOfRangeValuesFailClosedToDefaults() {
        FakeBackend backend = new FakeBackend();
        PlateSolverSettingsStore store = new PlateSolverSettingsStore(backend);
        String[] invalid = {
                "garbage",
                "5,80,110,55,7,2500",
                "4,80,110,55,7",
                "4,not-an-int,110,55,7,2500",
                "4,8,110,55,7,2500",
                "4,257,110,55,7,2500",
                "4,80,110,55,7,99999999999999999999",
                "3,129,111,55,7,2500",
                "2,257,110,55,7,2500",
                "2,8,110,55,7,2500"
        };

        for (String value : invalid) {
            backend.value = value;
            assertEquals(PlateSolverSettings.defaults(), store.load());
        }
    }

    @Test
    public void backendReadFailureFailsClosedAndResetClearsTheValue() {
        FakeBackend backend = new FakeBackend();
        PlateSolverSettingsStore store = new PlateSolverSettingsStore(backend);
        store.save(PlateSolverSettings.defaults().withTimeoutMillis(2_000));
        store.reset();
        assertNull(backend.value);

        backend.readFailure = new IllegalStateException("storage unavailable");
        assertEquals(PlateSolverSettings.defaults(), store.load());
    }
}
