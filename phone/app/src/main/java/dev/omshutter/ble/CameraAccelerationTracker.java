package dev.omshutter.ble;

import dev.omshutter.protocol.StarProtocol;
import dev.omshutter.solver.CameraGravityWindow;

/** BLE-owner admission and generation gate for mandatory STAR acceleration. */
final class CameraAccelerationTracker {
    private final CameraGravityWindow gravityWindow = new CameraGravityWindow();

    private StarProtocol.Acceleration latestSample;
    private long latestReceiptElapsedRealtimeNanos = -1L;
    private boolean fedGenerationKnown;
    private long fedMainGeneration;

    void accept(
            StarProtocol.Acceleration sample,
            long receiptElapsedRealtimeNanos) {
        if (sample == null) throw new IllegalArgumentException("acceleration sample is null");
        if (receiptElapsedRealtimeNanos < 0L) {
            throw new IllegalArgumentException("acceleration receipt timestamp is negative");
        }
        if (latestReceiptElapsedRealtimeNanos >= 0L
                && receiptElapsedRealtimeNanos < latestReceiptElapsedRealtimeNanos) {
            // Binder callbacks can be queued from different platform threads. The newer receipt
            // is authoritative; a late older callback must not rewind state or window time.
            return;
        }

        latestSample = sample;
        latestReceiptElapsedRealtimeNanos = receiptElapsedRealtimeNanos;
        if (!sample.isValid()) {
            clearGravityHistory();
            return;
        }

        // One main-generation value identifies one stock acceleration publication. A duplicate
        // notification/read may refresh receipt age, but must not manufacture another gravity
        // observation or extend the statistical window.
        if (fedGenerationKnown && sample.getMainGeneration() == fedMainGeneration) return;
        gravityWindow.addSample(
                sample.getXMillig(),
                sample.getYMillig(),
                sample.getZMillig(),
                receiptElapsedRealtimeNanos);
        fedMainGeneration = sample.getMainGeneration();
        fedGenerationKnown = true;
    }

    private void clearGravityHistory() {
        gravityWindow.clear();
        fedGenerationKnown = false;
        fedMainGeneration = 0L;
    }

    void clear() {
        latestSample = null;
        latestReceiptElapsedRealtimeNanos = -1L;
        clearGravityHistory();
    }

    StarProtocol.Acceleration getLatestSample() {
        return latestSample;
    }

    long getLatestReceiptElapsedRealtimeNanos() {
        return latestReceiptElapsedRealtimeNanos;
    }

    CameraGravityWindow.Estimate estimate(long nowElapsedRealtimeNanos) {
        return gravityWindow.estimate(nowElapsedRealtimeNanos);
    }

    int gravitySampleCount() {
        return gravityWindow.size();
    }
}
