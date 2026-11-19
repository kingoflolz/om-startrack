package dev.omshutter.ble;

import dev.omshutter.protocol.ExposureCatalogs;
import dev.omshutter.storage.CaptureRecoveryStore;

/** Pure ordering barrier for the persisted pre-STAR camera exposure tuple. */
final class CaptureRestoreBarrier {
    enum Observation { IGNORED, MATCHED, FRESH_BASELINE, MISMATCH, BLOCKED_UNKNOWN }
    private enum Phase { CLEAR, CAPTURE_IN_FLIGHT, RECOVERY_REQUIRED, BLOCKED_UNKNOWN }

    private Phase phase = Phase.CLEAR;
    private CaptureRecoveryStore.Obligation obligation;
    private boolean freshConnectionResetAllowed;

    void restore(CaptureRecoveryStore.LoadResult loaded) {
        if (loaded == null || loaded.getStatus() == CaptureRecoveryStore.Status.CORRUPT) {
            phase = Phase.BLOCKED_UNKNOWN;
            obligation = null;
        } else if (loaded.getStatus() == CaptureRecoveryStore.Status.PENDING) {
            restorePending(loaded.getObligation());
        } else {
            clear();
        }
    }

    void restorePending(CaptureRecoveryStore.Obligation pending) {
        if (pending == null) throw new IllegalArgumentException("obligation is null");
        phase = Phase.RECOVERY_REQUIRED;
        obligation = pending;
        freshConnectionResetAllowed = false;
    }

    void restoreUnknown() {
        phase = Phase.BLOCKED_UNKNOWN;
        obligation = null;
        freshConnectionResetAllowed = false;
    }

    void arm(CaptureRecoveryStore.Obligation next) {
        if (next == null) throw new IllegalArgumentException("obligation is null");
        obligation = next;
        phase = Phase.CAPTURE_IN_FLIGHT;
        freshConnectionResetAllowed = false;
    }

    void requirePostTerminalState() {
        if (phase == Phase.CAPTURE_IN_FLIGHT) {
            phase = Phase.RECOVERY_REQUIRED;
            freshConnectionResetAllowed = false;
        }
    }

    /**
     * Retires one accepted capture after its canonical terminal result.
     *
     * <p>OK, REJECTED_NO_MUTATION, and FAILED_SAFE certify exposure safety themselves. The
     * service clears the durable marker first and passes that result here so a process crash can
     * never leave memory less conservative than storage. FAILED_UNSAFE and every transport or
     * protocol failure still require an exact, ordered camera-state read.</p>
     */
    void finishTerminal(boolean exposureStateCertified, boolean durableMarkerCleared) {
        if (phase != Phase.CAPTURE_IN_FLIGHT) return;
        if (exposureStateCertified && durableMarkerCleared) {
            clear();
        } else {
            phase = Phase.RECOVERY_REQUIRED;
            freshConnectionResetAllowed = false;
        }
    }

    /**
     * Starts a new camera connection epoch.
     *
     * <p>A valid obligation may use one coherent authoritative STATE from its original camera as
     * the new baseline. This is deliberately armed only by a fresh connection, never by an
     * ordinary refresh in the connection where an unsafe or ambiguous capture occurred.</p>
     */
    void beginFreshConnection() {
        freshConnectionResetAllowed = phase == Phase.RECOVERY_REQUIRED && obligation != null;
    }

    /**
     * Observes the authoritative STATE obtained while initializing a fresh connection.
     *
     * <p>The service must durably clear the recovery record before calling {@link #clear()}.
     * Corrupt records, incoherent states, and states from another camera remain fail-closed.</p>
     */
    Observation observeFreshConnectionState(
            String cameraIdentity,
            ExposureCatalogs.Selection selection,
            boolean coherentAuthoritativeState) {
        if (phase == Phase.BLOCKED_UNKNOWN) return Observation.BLOCKED_UNKNOWN;
        if (phase != Phase.RECOVERY_REQUIRED || !freshConnectionResetAllowed) {
            return Observation.IGNORED;
        }
        if (!coherentAuthoritativeState || obligation == null || selection == null) {
            return Observation.IGNORED;
        }
        return obligation.getCameraIdentity().equals(cameraIdentity)
                ? Observation.FRESH_BASELINE : Observation.MISMATCH;
    }

    Observation observe(String cameraIdentity, ExposureCatalogs.Selection selection) {
        if (phase == Phase.BLOCKED_UNKNOWN) return Observation.BLOCKED_UNKNOWN;
        if (phase != Phase.RECOVERY_REQUIRED) return Observation.IGNORED;
        return obligation != null
                && obligation.getCameraIdentity().equals(cameraIdentity)
                && obligation.getSelection().equals(selection)
                ? Observation.MATCHED : Observation.MISMATCH;
    }

    Observation observePostTerminalState(
            String cameraIdentity,
            ExposureCatalogs.Selection selection,
            boolean orderedPostTerminalRead) {
        if (!orderedPostTerminalRead) return Observation.IGNORED;
        return observe(cameraIdentity, selection);
    }

    void clear() {
        phase = Phase.CLEAR;
        obligation = null;
        freshConnectionResetAllowed = false;
    }

    boolean isBlocking() { return phase != Phase.CLEAR; }
    boolean isCaptureInFlight() { return phase == Phase.CAPTURE_IN_FLIGHT; }
    boolean isRecoveryRequired() {
        return phase == Phase.RECOVERY_REQUIRED || phase == Phase.BLOCKED_UNKNOWN;
    }
    boolean isBlockedUnknown() { return phase == Phase.BLOCKED_UNKNOWN; }
    boolean canAcceptFreshConnectionBaseline() { return freshConnectionResetAllowed; }
    CaptureRecoveryStore.Obligation getObligation() { return obligation; }

    String recoveryDetail(
            String currentCameraIdentity,
            ExposureCatalogs.Selection currentSelection) {
        if (phase == Phase.BLOCKED_UNKNOWN) {
            return "STAR recovery record is unreadable; manually restore the camera, then clear this app's storage";
        }
        if (phase != Phase.RECOVERY_REQUIRED || obligation == null) return "";

        String expected = formatSelection(obligation.getSelection());
        if (!obligation.getCameraIdentity().equals(currentCameraIdentity)) {
            return "STAR recovery belongs to a different camera; reconnect the configured capture "
                    + "camera (only that camera can reset recovery), expected Shutter mode " + expected;
        }
        if (currentSelection == null) {
            return "STAR recovery is waiting for current camera settings; expected Shutter mode "
                    + expected + "; reconnect or restart the camera to establish a fresh baseline";
        }
        String current = formatSelection(currentSelection);
        if (obligation.getSelection().equals(currentSelection)) {
            return "STAR recovery settings match " + expected
                    + ", but the local recovery marker could not be cleared; retry, or reconnect or "
                    + "restart the camera";
        }
        return "STAR recovery: set Shutter mode to " + expected
                + " (currently " + current + "), or reconnect or restart the camera to accept its "
                + "fresh state as the new baseline";
    }

    private static String formatSelection(ExposureCatalogs.Selection selection) {
        return selection.getShutter() + " / " + selection.getAperture()
                + " / ISO " + selection.getIso();
    }
}
