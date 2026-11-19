package dev.omshutter.storage;

import android.content.Context;
import android.content.SharedPreferences;

import dev.omshutter.solver.CorrectionScale;

/** Service-owned persistent UI intent. Active correction leases are deliberately not stored. */
public final class AstroSettingsStore {
    private static final String FILE = "astro_assist_settings";
    private static final String ENABLED = "correction_enabled";
    private static final String FACTOR = "correction_factor_percent";
    private static final String OVERRIDE = "manual_override";
    private static final String MANUAL_RA = "manual_ra";
    private static final String MANUAL_DEC = "manual_dec";
    private static final String MANUAL_DIRTY = "manual_dirty";

    private final SharedPreferences preferences;

    public AstroSettingsStore(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean isCorrectionEnabled() {
        return preferences.getBoolean(ENABLED, true);
    }

    public void setCorrectionEnabled(boolean enabled) {
        preferences.edit().putBoolean(ENABLED, enabled).apply();
    }

    public int getFactorPercent() {
        int value = preferences.getInt(FACTOR, CorrectionScale.DEFAULT_PERCENT);
        return Math.max(CorrectionScale.MIN_PERCENT, Math.min(CorrectionScale.MAX_PERCENT, value));
    }

    public void setFactorPercent(int percent) {
        preferences.edit().putInt(FACTOR, CorrectionScale.requirePercent(percent)).apply();
    }

    public boolean isManualOverrideEnabled() {
        return preferences.getBoolean(OVERRIDE, false);
    }

    public void setManualOverrideEnabled(boolean enabled) {
        preferences.edit().putBoolean(OVERRIDE, enabled).apply();
    }

    public String getManualRa() { return preferences.getString(MANUAL_RA, ""); }
    public String getManualDec() { return preferences.getString(MANUAL_DEC, ""); }
    public boolean isManualDirty() { return preferences.getBoolean(MANUAL_DIRTY, false); }

    public void saveManualDraft(String ra, String dec, boolean dirty) {
        preferences.edit()
                .putString(MANUAL_RA, ra == null ? "" : ra)
                .putString(MANUAL_DEC, dec == null ? "" : dec)
                .putBoolean(MANUAL_DIRTY, dirty)
                .apply();
    }

}
