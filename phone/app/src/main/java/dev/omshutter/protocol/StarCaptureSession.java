package dev.omshutter.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure state machine for one serialized STAR capture CAPTURE_RECIPE operation.
 *
 * <p>The recipe remains camera-owned. The client binds the exact authoritative labels it read or
 * committed immediately before issuing CAPTURE_RECIPE so a successful result can describe the
 * temporary capture without trying to infer labels from opaque native values or restored STATE.
 * An OK terminal certifies restored exposure and completion of the camera's accepted zero-I/O
 * local rollover. The camera publishes authoritative restored STATE separately.
 */
public final class StarCaptureSession {
    public enum Phase {
        NEW,
        WAITING_FOR_RESULT,
        RECEIVING_PEAKS,
        COMPLETE,
        REJECTED,
        FAILED_SAFE,
        FAILED_UNSAFE,
        PROTOCOL_ERROR
    }

    public static final class Snapshot {
        private final Phase phase;
        private final List<StarCaptureProtocol.Peak> peaks;
        private final StarCaptureProtocol.CaptureMetadata capturedMetadata;
        private final ExposureCatalogs.Selection capturedSelection;
        private final StarCaptureProtocol.Outcome outcome;
        private final String detail;

        private Snapshot(
                Phase phase,
                List<StarCaptureProtocol.Peak> peaks,
                StarCaptureProtocol.CaptureMetadata capturedMetadata,
                ExposureCatalogs.Selection capturedSelection,
                StarCaptureProtocol.Outcome outcome,
                String detail) {
            this.phase = phase;
            this.peaks = Collections.unmodifiableList(new ArrayList<>(peaks));
            this.capturedMetadata = capturedMetadata;
            this.capturedSelection = capturedSelection;
            this.outcome = outcome;
            this.detail = detail;
        }

        public Phase getPhase() { return phase; }
        public List<StarCaptureProtocol.Peak> getPeaks() { return peaks; }
        public StarCaptureProtocol.CaptureMetadata getCapturedMetadata() {
            return capturedMetadata;
        }
        /** Exact camera-owned recipe labels qualified for this successful capture. */
        public ExposureCatalogs.Selection getCapturedSelection() {
            return capturedSelection;
        }
        public StarCaptureProtocol.Outcome getOutcome() { return outcome; }
        public String getDetail() { return detail; }
        public boolean isSuccessful() { return phase == Phase.COMPLETE; }
        /** True when a complete authoritative capture may be handed to the solver. */
        public boolean isSolveReady() { return isSuccessful(); }
        public boolean isTerminal() {
            return phase == Phase.COMPLETE
                    || phase == Phase.REJECTED
                    || phase == Phase.FAILED_SAFE
                    || phase == Phase.FAILED_UNSAFE
                    || phase == Phase.PROTOCOL_ERROR;
        }
        public boolean isExposureStateCertified() {
            return outcome != null && outcome.isExposureStateCertified();
        }
    }

    private final ArrayList<StarCaptureProtocol.Peak> peaks = new ArrayList<>();
    private Phase phase = Phase.NEW;
    private ExposureCatalogs.Selection captureRecipe;
    private StarCaptureProtocol.CaptureMetadata capturedMetadata;
    private StarCaptureProtocol.Outcome outcome;
    private String detail = "Ready to capture the camera-owned STAR recipe";

    /**
     * Starts this one-shot session and returns the complete command value for a GATT write with
     * response. Calling this more than once is a local programming error.
     */
    public synchronized byte[] beginCapture(ExposureCatalogs.Selection authoritativeRecipe) {
        if (phase != Phase.NEW) {
            throw new IllegalStateException("CAPTURE_RECIPE was already issued");
        }
        if (authoritativeRecipe == null) {
            throw new IllegalArgumentException("Authoritative STAR recipe is null");
        }
        captureRecipe = authoritativeRecipe;
        phase = Phase.WAITING_FOR_RESULT;
        detail = "Waiting for terminal capture result";
        return StarCaptureProtocol.captureRecipeRequest();
    }

    /** Parses and accepts the next ordered CONTROL indication. */
    public synchronized void acceptIndication(byte[] value)
            throws StarCaptureProtocol.ProtocolException {
        StarCaptureProtocol.ControlIndication indication;
        try {
            indication = StarCaptureProtocol.parseControlIndication(value);
        } catch (StarCaptureProtocol.ProtocolException error) {
            protocolError(error.getMessage());
            throw error;
        }
        acceptIndication(indication);
    }

    /** Accepts a previously parsed ordered CONTROL indication. */
    public synchronized void acceptIndication(StarCaptureProtocol.ControlIndication indication)
            throws StarCaptureProtocol.ProtocolException {
        if (indication == null) {
            failProtocol("CONTROL indication is null");
        }
        if (phase != Phase.WAITING_FOR_RESULT && phase != Phase.RECEIVING_PEAKS) {
            failProtocol("CONTROL indication has no in-flight CAPTURE_RECIPE");
        }

        if (indication instanceof StarCaptureProtocol.PeakBatch) {
            acceptPeakBatch((StarCaptureProtocol.PeakBatch) indication);
            return;
        }
        if (indication instanceof StarCaptureProtocol.Terminal) {
            acceptTerminal((StarCaptureProtocol.Terminal) indication);
            return;
        }
        failProtocol("Unknown parsed CONTROL indication");
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                phase,
                peaks,
                capturedMetadata,
                phase == Phase.COMPLETE ? captureRecipe : null,
                outcome,
                detail);
    }

    /**
     * Fails closed when the GATT transaction ends without a canonical terminal indication.
     * Such a failure never proves a terminal outcome or exposure-state certificate.
     */
    public synchronized void transportFailed(String reason) {
        if (phase == Phase.COMPLETE
                || phase == Phase.REJECTED
                || phase == Phase.FAILED_SAFE
                || phase == Phase.FAILED_UNSAFE) {
            return;
        }
        protocolError(reason == null || reason.isEmpty()
                ? "STAR capture transport ended before a terminal result"
                : reason);
    }

    private void acceptPeakBatch(StarCaptureProtocol.PeakBatch batch)
            throws StarCaptureProtocol.ProtocolException {
        List<StarCaptureProtocol.Peak> received = batch.getPeaks();
        if (peaks.size() + received.size() > StarCaptureProtocol.MAX_PEAKS) {
            failProtocol("Ordered peak stream exceeds 256 records");
        }
        if (!peaks.isEmpty()
                && received.get(0).getFlux() > peaks.get(peaks.size() - 1).getFlux()) {
            failProtocol("Peak flux increases across indication boundary");
        }
        peaks.addAll(received);
        phase = Phase.RECEIVING_PEAKS;
        detail = "Received " + peaks.size() + " peaks; waiting for terminal result";
    }

    private void acceptTerminal(StarCaptureProtocol.Terminal terminal)
            throws StarCaptureProtocol.ProtocolException {
        StarCaptureProtocol.Outcome receivedOutcome = terminal.getOutcome();
        if (receivedOutcome == StarCaptureProtocol.Outcome.OK) {
            if (terminal.getTotal() != peaks.size()) {
                failProtocol("TERMINAL total does not match the ordered peak stream");
            }
            capturedMetadata = terminal.getMetadata();
            outcome = receivedOutcome;
            phase = Phase.COMPLETE;
            detail = "Capture complete and exposure restored";
            return;
        }

        if (!peaks.isEmpty()) {
            failProtocol("Failed capture published peaks before its terminal result");
        }
        outcome = receivedOutcome;
        switch (receivedOutcome) {
            case REJECTED_NO_MUTATION:
                phase = Phase.REJECTED;
                detail = "Capture rejected before camera mutation";
                return;
            case FAILED_SAFE:
                phase = Phase.FAILED_SAFE;
                detail = "Capture failed and camera restoration is safe";
                return;
            case FAILED_UNSAFE:
                phase = Phase.FAILED_UNSAFE;
                detail = "Capture failed without safe restoration proof; camera recovery is required";
                return;
            default:
                failProtocol("Unexpected non-success terminal outcome");
        }
    }

    private void failProtocol(String message) throws StarCaptureProtocol.ProtocolException {
        protocolError(message);
        throw new StarCaptureProtocol.ProtocolException(message);
    }

    private void protocolError(String message) {
        phase = Phase.PROTOCOL_ERROR;
        capturedMetadata = null;
        outcome = null;
        detail = message == null ? "STAR capture protocol error" : message;
    }
}
