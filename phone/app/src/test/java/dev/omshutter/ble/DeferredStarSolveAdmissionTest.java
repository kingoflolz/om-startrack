package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.omshutter.protocol.AstroRateSetpoint;

import org.junit.Test;

public final class DeferredStarSolveAdmissionTest {
    private static final int EPOCH = 9;

    @Test
    public void successfulBoundaryConsumesExactlyOneSolveCaptureIntent() {
        CameraConnectionService.DeferredStarSolveAdmission admission =
                new CameraConnectionService.DeferredStarSolveAdmission();

        assertTrue(admission.defer(17L, null, true));
        assertTrue(admission.isPending());
        assertFalse(admission.defer(18L, null, false));

        CameraConnectionService.DeferredStarSolveAdmission.Request request =
                admission.consume();
        assertEquals(17L, request.intentId);
        assertTrue(request.captureAfterSolve);
        assertFalse(admission.isPending());
        assertNull(admission.consume());
    }

    @Test
    public void failureRejectsAndDisconnectClearWithoutReplay() {
        CameraConnectionService.DeferredStarSolveAdmission admission =
                new CameraConnectionService.DeferredStarSolveAdmission();

        assertTrue(admission.defer(21L, null, false));
        assertEquals(21L, admission.reject().intentId);
        assertNull(admission.reject());

        assertTrue(admission.defer(22L, null, true));
        admission.clear();
        assertFalse(admission.isPending());
        assertNull(admission.consume());
    }

    @Test
    public void queuedThenInFlightRenewalAdmitsOnlyAfterSuccessfulAck() {
        AstroRateLease lease = acknowledgedLease();
        CameraConnectionService.DeferredStarSolveAdmission admission =
                new CameraConnectionService.DeferredStarSolveAdmission();

        assertTrue(lease.queueRenewal(EPOCH));
        assertTrue(lease.isCaptureStartWaitingForHeartbeat(EPOCH));
        assertTrue(admission.defer(31L, null, true));
        assertFalse(lease.isCaptureStartReady(EPOCH));

        lease.startNext(EPOCH);
        assertTrue(lease.isCaptureStartWaitingForHeartbeat(EPOCH));
        assertFalse(lease.isCaptureStartReady(EPOCH));
        assertTrue(lease.complete(EPOCH, true).isLatestDesired());

        assertTrue(lease.isCaptureStartReady(EPOCH));
        CameraConnectionService.DeferredStarSolveAdmission.Request request =
                admission.consume();
        assertEquals(31L, request.intentId);
        assertTrue(request.captureAfterSolve);
        assertTrue(lease.beginCaptureHold(EPOCH, 200L));
        assertNull(admission.consume());
    }

    @Test
    public void failedRenewalRejectsWithoutCrossingStrictBoundary() {
        AstroRateLease lease = acknowledgedLease();
        CameraConnectionService.DeferredStarSolveAdmission admission =
                new CameraConnectionService.DeferredStarSolveAdmission();

        assertTrue(lease.queueRenewal(EPOCH));
        lease.startNext(EPOCH);
        assertTrue(admission.defer(41L, null, false));
        assertFalse(lease.complete(EPOCH, false).isSuccess());

        assertFalse(lease.isCaptureStartReady(EPOCH));
        assertFalse(lease.isCaptureStartWaitingForHeartbeat(EPOCH));
        assertEquals(41L, admission.reject().intentId);
        assertNull(admission.consume());
    }

    @Test
    public void hiddenActivityRetainsSolveButCancelsDeferredStockCapture() {
        CameraConnectionService.DeferredStarSolveAdmission admission =
                new CameraConnectionService.DeferredStarSolveAdmission();
        assertTrue(admission.defer(45L, null, true));

        admission.cancelCaptureAfterSolve();

        CameraConnectionService.DeferredStarSolveAdmission.Request request =
                admission.consume();
        assertEquals(45L, request.intentId);
        assertFalse(request.captureAfterSolve);
    }

    @Test
    public void adaptiveHeartbeatQueuedAndInFlightAdmitsOnlyAfterAck() {
        AstroRateLease lease = acknowledgedLease();
        CameraConnectionService.DeferredStarSolveAdmission admission =
                new CameraConnectionService.DeferredStarSolveAdmission();
        byte[] adaptiveValue = new byte[18];
        adaptiveValue[0] = 2;
        adaptiveValue[12] = 1;

        assertTrue(lease.offerHeartbeatSetpoint(
                4L,
                EPOCH,
                AstroRateSetpoint.gaveOnly(2, -1),
                adaptiveValue));
        assertTrue(lease.isCaptureStartWaitingForHeartbeat(EPOCH));
        assertTrue(admission.defer(51L, null, false));

        assertTrue(lease.startNext(EPOCH).isHeartbeat());
        assertTrue(lease.isCaptureStartWaitingForHeartbeat(EPOCH));
        assertTrue(lease.complete(EPOCH, true).isLatestDesired());

        assertTrue(lease.isCaptureStartReady(EPOCH));
        assertEquals(51L, admission.consume().intentId);
        assertTrue(lease.beginCaptureHold(EPOCH, 201L));
    }

    private static AstroRateLease acknowledgedLease() {
        AstroRateLease lease = new AstroRateLease();
        byte[] value = new byte[18];
        value[0] = 1;
        value[12] = 1;
        lease.offerSetpoint(4L, EPOCH, value);
        lease.startNext(EPOCH);
        lease.complete(EPOCH, true);
        return lease;
    }
}
