package dev.omshutter.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class StarCaptureSessionTest {
    @Test
    public void captureCommandReferencesOnlyTheCameraOwnedRecipe() {
        StarCaptureSession session = new StarCaptureSession();
        assertEquals(
                "Ready to capture the camera-owned STAR recipe",
                session.snapshot().getDetail());
        assertArrayEquals(new byte[]{0x01}, session.beginCapture(recipe()));
    }

    @Test
    public void maximumCaptureIsOneRequestOrderedPeaksAndOneTerminal() throws Exception {
        StarCaptureSession session = new StarCaptureSession();
        assertEquals(StarCaptureSession.Phase.NEW, session.snapshot().getPhase());
        ExposureCatalogs.Selection recipe = recipe();
        assertArrayEquals(new byte[]{0x01}, session.beginCapture(recipe));
        assertNull(session.snapshot().getCapturedSelection());

        int first = 0;
        long flux = 10_000;
        int[] counts = {63, 63, 63, 63, 4};
        for (int count : counts) {
            session.acceptIndication(StarCaptureProtocolTest.peaks(first, count, flux));
            first += count;
            flux -= count;
        }
        assertEquals(
                StarCaptureSession.Phase.RECEIVING_PEAKS,
                session.snapshot().getPhase());
        assertEquals(256, session.snapshot().getPeaks().size());
        assertFalse(session.snapshot().isTerminal());
        assertFalse(session.snapshot().isExposureStateCertified());

        session.acceptIndication(StarCaptureProtocolTest.successTerminal(
                256, -2, 3, 4, Float.floatToRawIntBits(45.0f), "M.ZUIKO 45mm"));

        StarCaptureSession.Snapshot complete = session.snapshot();
        assertEquals(StarCaptureSession.Phase.COMPLETE, complete.getPhase());
        assertEquals(StarCaptureProtocol.Outcome.OK, complete.getOutcome());
        assertTrue(complete.isTerminal());
        assertTrue(complete.isExposureStateCertified());
        assertTrue(complete.isSuccessful());
        assertTrue(complete.isSolveReady());
        assertEquals(recipe, complete.getCapturedSelection());
        assertEquals(256, complete.getPeaks().size());
        assertEquals(0, complete.getPeaks().get(0).getX());
        assertEquals(255, complete.getPeaks().get(255).getX());
        StarCaptureProtocol.CaptureMetadata metadata = complete.getCapturedMetadata();
        assertNotNull(metadata);
        assertEquals(-2, metadata.getShutterValue());
        assertEquals(3, metadata.getApertureValue());
        assertEquals(4, metadata.getIsoValue());
        assertEquals(45.0f, metadata.getFocalLengthMm(), 0.0f);
        assertEquals("M.ZUIKO 45mm", metadata.getLensName());
    }

    @Test
    public void emptyCaptureCompletesDirectlyFromTerminal() throws Exception {
        StarCaptureSession session = started();
        session.acceptIndication(StarCaptureProtocolTest.successTerminal(0, 1, 2, 3, 0, ""));
        assertTrue(session.snapshot().isSuccessful());
        assertTrue(session.snapshot().getPeaks().isEmpty());
        assertFalse(session.snapshot().getCapturedMetadata().hasFocalLength());
        assertFalse(session.snapshot().getCapturedMetadata().hasLensName());
    }

    @Test
    public void beginCaptureMayOnlyBeCalledOnce() {
        StarCaptureSession session = started();
        try {
            session.beginCapture(recipe());
            fail("Expected duplicate request rejection");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("already issued"));
        }
    }

    @Test
    public void beginCaptureRequiresAnAuthoritativeRecipe() {
        StarCaptureSession session = new StarCaptureSession();
        try {
            session.beginCapture(null);
            fail("Expected null authoritative recipe rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("recipe"));
        }
        assertEquals(StarCaptureSession.Phase.NEW, session.snapshot().getPhase());
        assertNull(session.snapshot().getCapturedSelection());
    }

    @Test
    public void terminalOutcomesEncodeExposureStateCertification() throws Exception {
        assertFailure(
                1,
                StarCaptureSession.Phase.REJECTED,
                StarCaptureProtocol.Outcome.REJECTED_NO_MUTATION,
                true);
        assertFailure(
                2,
                StarCaptureSession.Phase.FAILED_SAFE,
                StarCaptureProtocol.Outcome.FAILED_SAFE,
                true);
        assertFailure(
                3,
                StarCaptureSession.Phase.FAILED_UNSAFE,
                StarCaptureProtocol.Outcome.FAILED_UNSAFE,
                false);
    }

    @Test
    public void failedUnsafeEndsOneShotGenerationAndFreshExplicitGenerationCanStart()
            throws Exception {
        StarCaptureSession failed = started();
        failed.acceptIndication(new byte[]{
                (byte) 0x82,
                (byte) StarCaptureProtocol.Outcome.FAILED_UNSAFE.getWireValue(),
                0,
                0});

        assertEquals(StarCaptureSession.Phase.FAILED_UNSAFE, failed.snapshot().getPhase());
        assertTrue(failed.snapshot().isTerminal());
        assertTrue(failed.snapshot().getDetail().contains("recovery is required"));
        try {
            failed.beginCapture(recipe());
            fail("Expected the failed one-shot generation not to replay");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("already issued"));
        }

        StarCaptureSession explicitlyRequestedReplacement = new StarCaptureSession();
        assertArrayEquals(
                new byte[]{(byte) StarCaptureProtocol.CAPTURE_RECIPE},
                explicitlyRequestedReplacement.beginCapture(recipe()));
        assertEquals(
                StarCaptureSession.Phase.WAITING_FOR_RESULT,
                explicitlyRequestedReplacement.snapshot().getPhase());
    }

    @Test
    public void totalMismatchAndFailureAfterPeaksFailClosed() throws Exception {
        StarCaptureSession mismatch = started();
        mismatch.acceptIndication(StarCaptureProtocolTest.peaks(0, 1, 10));
        assertSessionError(
                mismatch,
                "does not match",
                StarCaptureProtocolTest.successTerminal(2, 1, 2, 3, 0, ""));

        StarCaptureSession failedAfterPeak = started();
        failedAfterPeak.acceptIndication(StarCaptureProtocolTest.peaks(0, 1, 10));
        assertSessionError(
                failedAfterPeak,
                "published peaks",
                new byte[]{(byte) 0x82, 2, 0, 0});
    }

    @Test
    public void crossIndicationFluxIncreaseFailsClosed() throws Exception {
        StarCaptureSession session = started();
        session.acceptIndication(StarCaptureProtocolTest.peaks(0, 2, 10));
        assertSessionError(
                session,
                "across indication",
                StarCaptureProtocolTest.peaks(2, 1, 10));
    }

    @Test
    public void streamCannotExceedMaximumBeforeTerminal() throws Exception {
        StarCaptureSession session = started();
        long flux = 1000;
        int first = 0;
        for (int ignored = 0; ignored < 4; ignored++) {
            session.acceptIndication(StarCaptureProtocolTest.peaks(first, 63, flux));
            first += 63;
            flux -= 63;
        }
        assertSessionError(
                session,
                "exceeds 256",
                StarCaptureProtocolTest.peaks(first, 5, flux));
    }

    @Test
    public void malformedTransportAndPostTerminalInputDoNotProveCleanup() throws Exception {
        StarCaptureSession malformed = started();
        assertSessionError(malformed, "Unknown CONTROL", new byte[]{0x55});
        assertTrue(malformed.snapshot().isTerminal());
        assertFalse(malformed.snapshot().isExposureStateCertified());
        assertNull(malformed.snapshot().getOutcome());

        StarCaptureSession disconnected = started();
        disconnected.transportFailed("link lost");
        assertEquals(StarCaptureSession.Phase.PROTOCOL_ERROR,
                disconnected.snapshot().getPhase());
        assertEquals("link lost", disconnected.snapshot().getDetail());
        assertFalse(disconnected.snapshot().isExposureStateCertified());

        StarCaptureSession complete = started();
        complete.acceptIndication(StarCaptureProtocolTest.successTerminal(0, 1, 2, 3, 0, ""));
        assertSessionError(complete, "no in-flight", StarCaptureProtocolTest.peaks(0, 1, 10));
        assertFalse(complete.snapshot().isExposureStateCertified());
        assertNull(complete.snapshot().getCapturedSelection());
    }

    @Test
    public void successfulCaptureKeepsExactRecipeLabelsWhenRestoredStateDiffers()
            throws Exception {
        ExposureCatalogs.Selection temporaryRecipe = new ExposureCatalogs.Selection(
                "4\"", "F2.8", "3200");
        ExposureCatalogs.Selection restoredLiveState = new ExposureCatalogs.Selection(
                "1/125", "F8.0", "200");
        StarCaptureSession session = new StarCaptureSession();
        session.beginCapture(temporaryRecipe);

        // DONE carries only native setting identities plus optics; restored STATE intentionally
        // returns to a different persistent selection after the temporary recipe capture.
        session.acceptIndication(StarCaptureProtocolTest.successTerminal(
                0, -123, 456, 789, Float.floatToRawIntBits(25.0f), "M.ZUIKO 25mm"));

        StarCaptureSession.Snapshot complete = session.snapshot();
        assertTrue(complete.isSuccessful());
        assertNotNull(complete.getCapturedMetadata());
        assertEquals(temporaryRecipe, complete.getCapturedSelection());
        assertFalse(complete.getCapturedSelection().equals(restoredLiveState));
    }

    private static StarCaptureSession started() {
        StarCaptureSession session = new StarCaptureSession();
        session.beginCapture(recipe());
        return session;
    }

    private static ExposureCatalogs.Selection recipe() {
        return new ExposureCatalogs.Selection("1/30", "F4.0", "800");
    }

    private static void assertFailure(
            int wireOutcome,
            StarCaptureSession.Phase phase,
            StarCaptureProtocol.Outcome outcome,
            boolean exposureStateCertified)
            throws Exception {
        StarCaptureSession session = started();
        session.acceptIndication(new byte[]{(byte) 0x82, (byte) wireOutcome, 0, 0});
        assertEquals(phase, session.snapshot().getPhase());
        assertEquals(outcome, session.snapshot().getOutcome());
        assertTrue(session.snapshot().isTerminal());
        assertEquals(
                exposureStateCertified,
                session.snapshot().isExposureStateCertified());
        assertFalse(session.snapshot().isSuccessful());
        assertNull(session.snapshot().getCapturedMetadata());
        assertNull(session.snapshot().getCapturedSelection());
    }

    private static void assertSessionError(
            StarCaptureSession session, String text, byte[] indication) {
        try {
            session.acceptIndication(indication);
            fail("Expected session error containing: " + text);
        } catch (StarCaptureProtocol.ProtocolException expected) {
            assertTrue(
                    "Actual message: " + expected.getMessage(),
                    expected.getMessage().contains(text));
        }
        assertEquals(StarCaptureSession.Phase.PROTOCOL_ERROR,
                session.snapshot().getPhase());
        assertFalse(session.snapshot().isExposureStateCertified());
    }
}
