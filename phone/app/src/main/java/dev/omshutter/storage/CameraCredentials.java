package dev.omshutter.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores the camera passcode encrypted by an app-private Android Keystore key. */
public final class CameraCredentials {
    private static final String TAG = "CameraCredentials";
    private static final String PREFS = "camera_connection";
    private static final String KEY_ALIAS = "om_ble_shutter_passcode_v1";
    private static final String KEY_NAME = "name";
    private static final String KEY_ADDRESS = "address";
    private static final String KEY_CIPHER = "passcode_cipher";
    private static final String KEY_IV = "passcode_iv";
    private static final String KEY_DESIRED = "desired_connected";
    private static final String KEY_PENDING_RELEASE = "pending_release_action";
    private static final String KEY_PENDING_RELEASE_URGENT_UNTIL =
            "pending_release_urgent_until_epoch_ms";
    private static final String KEY_PENDING_RELEASE_URGENT_UNTIL_ELAPSED =
            "pending_release_urgent_until_elapsed_ms";
    private static final String KEY_PENDING_RELEASE_URGENT_BOOT =
            "pending_release_urgent_boot_count";

    private final SharedPreferences preferences;

    public CameraCredentials(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(String name, String address, String passcode)
            throws GeneralSecurityException {
        String cleanName = name == null ? "" : name.trim();
        String cleanAddress = address == null ? "" : address.trim();
        SharedPreferences.Editor editor = preferences.edit()
                .putString(KEY_NAME, cleanName)
                .putString(KEY_ADDRESS, cleanAddress);

        if (passcode != null && !passcode.isEmpty()) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(passcode.getBytes(StandardCharsets.UTF_8));
            editor.putString(KEY_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP));
            editor.putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        }
        if (!editor.commit()) {
            throw new GeneralSecurityException("Could not persist camera settings");
        }
    }

    public synchronized SavedCamera load() {
        String name = preferences.getString(KEY_NAME, "");
        String address = preferences.getString(KEY_ADDRESS, "");
        String passcode = decryptPasscode();
        return new SavedCamera(name, address, passcode);
    }

    public synchronized void updateAddress(String address) {
        preferences.edit().putString(KEY_ADDRESS, address == null ? "" : address).apply();
    }

    public boolean isDesiredConnected() {
        return preferences.getBoolean(KEY_DESIRED, false);
    }

    public void setDesiredConnected(boolean desired) {
        preferences.edit().putBoolean(KEY_DESIRED, desired).apply();
    }

    /**
     * Persists a conservative release that must be sent after a cold process restart.
     * This is intentionally written before a press reaches GATT and cleared only after
     * a confirmed release result.
     */
    public int getPendingReleaseAction() {
        return preferences.getInt(KEY_PENDING_RELEASE, 0);
    }

    public long getPendingReleaseUrgentUntilEpochMs() {
        return preferences.getLong(KEY_PENDING_RELEASE_URGENT_UNTIL, 0L);
    }

    public long getPendingReleaseUrgentUntilElapsedMs() {
        return preferences.getLong(KEY_PENDING_RELEASE_URGENT_UNTIL_ELAPSED, 0L);
    }

    public int getPendingReleaseUrgentBootCount() {
        return preferences.getInt(KEY_PENDING_RELEASE_URGENT_BOOT, -1);
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    public boolean setPendingReleaseState(
            int action,
            long urgentUntilEpochMs,
            long urgentUntilElapsedMs,
            int urgentBootCount) {
        // This marker is safety-critical and tiny; commit before issuing the GATT write.
        SharedPreferences.Editor editor = preferences.edit();
        if (action == 0) {
            editor.remove(KEY_PENDING_RELEASE)
                    .remove(KEY_PENDING_RELEASE_URGENT_UNTIL)
                    .remove(KEY_PENDING_RELEASE_URGENT_UNTIL_ELAPSED)
                    .remove(KEY_PENDING_RELEASE_URGENT_BOOT);
        } else {
            editor.putInt(KEY_PENDING_RELEASE, action);
            if (urgentUntilEpochMs > 0L && urgentUntilElapsedMs > 0L) {
                editor.putLong(KEY_PENDING_RELEASE_URGENT_UNTIL, urgentUntilEpochMs)
                        .putLong(
                            KEY_PENDING_RELEASE_URGENT_UNTIL_ELAPSED,
                            urgentUntilElapsedMs)
                        .putInt(KEY_PENDING_RELEASE_URGENT_BOOT, urgentBootCount);
            } else {
                editor.remove(KEY_PENDING_RELEASE_URGENT_UNTIL)
                        .remove(KEY_PENDING_RELEASE_URGENT_UNTIL_ELAPSED)
                        .remove(KEY_PENDING_RELEASE_URGENT_BOOT);
            }
        }
        return editor.commit();
    }

    public void clear() {
        preferences.edit().clear().apply();
    }

    private String decryptPasscode() {
        String encodedCipher = preferences.getString(KEY_CIPHER, null);
        String encodedIv = preferences.getString(KEY_IV, null);
        if (encodedCipher == null || encodedIv == null) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    new GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP)));
            byte[] clear = cipher.doFinal(Base64.decode(encodedCipher, Base64.NO_WRAP));
            return new String(clear, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            Log.e(TAG, "Stored passcode could not be decrypted", error);
            return "";
        }
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        try {
            keyStore.load(null);
        } catch (java.io.IOException error) {
            throw new GeneralSecurityException("Could not load Android Keystore", error);
        }
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    public static final class SavedCamera {
        public final String name;
        public final String address;
        public final String passcode;

        private SavedCamera(String name, String address, String passcode) {
            this.name = name == null ? "" : name;
            this.address = address == null ? "" : address;
            this.passcode = passcode == null ? "" : passcode;
        }

        public boolean isComplete() {
            return !name.isEmpty() && !passcode.isEmpty();
        }
    }
}
