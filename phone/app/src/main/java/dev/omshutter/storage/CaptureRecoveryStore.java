package dev.omshutter.storage;

import android.content.Context;
import android.content.SharedPreferences;

import dev.omshutter.protocol.ExposureCatalogs;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** Crash-safe pre-STAR exposure tuple, committed before CAPTURE_RECIPE reaches GATT. */
public final class CaptureRecoveryStore {
    private static final String FILE = "star_capture_recovery";
    private static final String RECORD = "pending_v1";
    private static final String VERSION = "1";

    public enum Status { EMPTY, PENDING, CORRUPT }

    public static final class Obligation {
        private final String cameraIdentity;
        private final ExposureCatalogs.Selection selection;

        public Obligation(String cameraIdentity, ExposureCatalogs.Selection selection) {
            if (cameraIdentity == null || cameraIdentity.isEmpty()) {
                throw new IllegalArgumentException("camera identity is empty");
            }
            if (selection == null) throw new IllegalArgumentException("selection is null");
            this.cameraIdentity = cameraIdentity;
            this.selection = selection;
        }

        public String getCameraIdentity() { return cameraIdentity; }
        public ExposureCatalogs.Selection getSelection() { return selection; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Obligation)) return false;
            Obligation that = (Obligation) other;
            return cameraIdentity.equals(that.cameraIdentity)
                    && selection.equals(that.selection);
        }

        @Override
        public int hashCode() {
            return Objects.hash(cameraIdentity, selection);
        }
    }

    public static final class LoadResult {
        private final Status status;
        private final Obligation obligation;

        private LoadResult(Status status, Obligation obligation) {
            this.status = status;
            this.obligation = obligation;
        }

        public Status getStatus() { return status; }
        public Obligation getObligation() { return obligation; }
    }

    interface Backend {
        String read();
        boolean write(String value);
        boolean clear();
    }

    private static final class SharedPreferencesBackend implements Backend {
        private final SharedPreferences preferences;

        SharedPreferencesBackend(Context context) {
            preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }

        @Override public String read() { return preferences.getString(RECORD, ""); }
        @Override public boolean write(String value) {
            return preferences.edit().putString(RECORD, value).commit();
        }
        @Override public boolean clear() {
            return preferences.edit().remove(RECORD).commit();
        }
    }

    private final Backend backend;

    public CaptureRecoveryStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context is null");
        backend = new SharedPreferencesBackend(context.getApplicationContext());
    }

    CaptureRecoveryStore(Backend backend) {
        if (backend == null) throw new IllegalArgumentException("backend is null");
        this.backend = backend;
    }

    public LoadResult load() {
        final String encoded;
        try {
            encoded = backend.read();
        } catch (RuntimeException error) {
            return new LoadResult(Status.CORRUPT, null);
        }
        if (encoded == null || encoded.isEmpty()) {
            return new LoadResult(Status.EMPTY, null);
        }
        try {
            String[] fields = encoded.split(",", -1);
            if (fields.length != 5 || !VERSION.equals(fields[0])) {
                return new LoadResult(Status.CORRUPT, null);
            }
            ExposureCatalogs.Selection selection = new ExposureCatalogs.Selection(
                    decode(fields[2]), decode(fields[3]), decode(fields[4]));
            return new LoadResult(
                    Status.PENDING,
                    new Obligation(decode(fields[1]), selection));
        } catch (IllegalArgumentException error) {
            return new LoadResult(Status.CORRUPT, null);
        }
    }

    /** Returns only after the recovery marker has reached app-private durable storage. */
    public boolean arm(Obligation obligation) {
        if (obligation == null) throw new IllegalArgumentException("obligation is null");
        try {
            return backend.write(encode(obligation));
        } catch (RuntimeException error) {
            return false;
        }
    }

    public boolean clear() {
        try {
            return backend.clear();
        } catch (RuntimeException error) {
            return false;
        }
    }

    static String encode(Obligation obligation) {
        ExposureCatalogs.Selection selection = obligation.getSelection();
        return VERSION + ','
                + encodeField(obligation.getCameraIdentity()) + ','
                + encodeField(selection.getShutter()) + ','
                + encodeField(selection.getAperture()) + ','
                + encodeField(selection.getIso());
    }

    private static String encodeField(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
