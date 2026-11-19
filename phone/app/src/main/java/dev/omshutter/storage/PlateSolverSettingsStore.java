package dev.omshutter.storage;

import android.content.Context;
import android.content.SharedPreferences;

import dev.omshutter.solver.PlateSolverSettings;

/** Persistent local-phone plate-solver settings. Camera detector settings are unrelated. */
public final class PlateSolverSettingsStore {
    private static final String FILE = "plate_solver_settings";
    private static final String CONFIG = "config_v1";
    private static final int LEGACY_MAX_OBSERVATIONS = 256;
    private static final int VERSION_3_MAX_OBSERVATIONS = 128;
    // Version 4 restores the complete 256-source solve population.
    // Version-1/2/3 records retain their other valid knobs, but their legacy
    // observation count is deliberately replaced by the new default.
    private static final String VERSION = "4";

    interface Backend {
        String read();
        void write(String value);
        void clear();
    }

    private static final class SharedPreferencesBackend implements Backend {
        private final SharedPreferences preferences;

        SharedPreferencesBackend(Context context) {
            preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }

        @Override
        public String read() {
            return preferences.getString(CONFIG, "");
        }

        @Override
        public void write(String value) {
            preferences.edit().putString(CONFIG, value).apply();
        }

        @Override
        public void clear() {
            preferences.edit().remove(CONFIG).apply();
        }
    }

    private final Backend backend;

    public PlateSolverSettingsStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context is null");
        backend = new SharedPreferencesBackend(context.getApplicationContext());
    }

    PlateSolverSettingsStore(Backend backend) {
        if (backend == null) throw new IllegalArgumentException("backend is null");
        this.backend = backend;
    }

    /** Returns defaults if storage is absent, malformed, from another version, or out of range. */
    public PlateSolverSettings load() {
        final String encoded;
        try {
            encoded = backend.read();
        } catch (RuntimeException error) {
            return PlateSolverSettings.defaults();
        }
        if (encoded == null || encoded.isEmpty()) return PlateSolverSettings.defaults();
        String[] fields = encoded.split(",", -1);
        if (fields.length != 6) {
            return PlateSolverSettings.defaults();
        }
        try {
            int seedToleranceArcsec = Integer.parseInt(fields[2]);
            int matchToleranceArcsec = Integer.parseInt(fields[3]);
            int requiredIndependentMatches = Integer.parseInt(fields[4]);
            int timeoutMillis = Integer.parseInt(fields[5]);
            int maxObservations;
            if (VERSION.equals(fields[0])) {
                maxObservations = Integer.parseInt(fields[1]);
            } else if ("1".equals(fields[0])
                    || "2".equals(fields[0])
                    || "3".equals(fields[0])) {
                // Parse the retired value so malformed legacy records still
                // fail closed, but never carry it into the new solve policy.
                int legacyMaxObservations = Integer.parseInt(fields[1]);
                int legacyUpperBound = "3".equals(fields[0])
                        ? VERSION_3_MAX_OBSERVATIONS
                        : LEGACY_MAX_OBSERVATIONS;
                if (legacyMaxObservations < PlateSolverSettings.MIN_MAX_OBSERVATIONS
                        || legacyMaxObservations > legacyUpperBound) {
                    return PlateSolverSettings.defaults();
                }
                if (requiredIndependentMatches > legacyMaxObservations - 4) {
                    return PlateSolverSettings.defaults();
                }
                maxObservations = PlateSolverSettings.DEFAULT_MAX_OBSERVATIONS;
            } else {
                return PlateSolverSettings.defaults();
            }
            return new PlateSolverSettings(
                    maxObservations,
                    seedToleranceArcsec,
                    matchToleranceArcsec,
                    requiredIndependentMatches,
                    timeoutMillis);
        } catch (IllegalArgumentException error) {
            return PlateSolverSettings.defaults();
        }
    }

    /** Atomically replaces the complete configuration from the application's perspective. */
    public void save(PlateSolverSettings settings) {
        if (settings == null) throw new IllegalArgumentException("settings are null");
        backend.write(encode(settings));
    }

    public void reset() {
        backend.clear();
    }

    static String encode(PlateSolverSettings settings) {
        return VERSION
                + "," + settings.getMaxObservations()
                + "," + settings.getSeedToleranceArcsec()
                + "," + settings.getMatchToleranceArcsec()
                + "," + settings.getRequiredIndependentMatches()
                + "," + settings.getTimeoutMillis();
    }
}
