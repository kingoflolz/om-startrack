package dev.omshutter.solver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.omshutter.protocol.ExposureCatalogs;
import dev.omshutter.protocol.StarCaptureSession;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public final class StarSolveRequestAdapterTest {
    @Test
    public void copiesCanonicalPeaksAndCanonicalizesLensName() throws Exception {
        StarCaptureSession session = new StarCaptureSession();
        session.beginCapture(recipe());
        session.acceptIndication(peaks(
                new int[]{10, 30, 50},
                new int[]{20, 40, 60},
                new long[]{100, 50, 25}));
        session.acceptIndication(successTerminal(3, "  M.ZUIKO 45mm  "));

        StarSolveRequestAdapter.Adapted adapted =
                StarSolveRequestAdapter.adapt(7, session.snapshot());
        SolveRequest request = adapted.getRequest();

        assertFalse(adapted.isLensMetadataMissing());
        assertEquals("M.ZUIKO 45mm", request.getLensName());
        assertEquals(3, request.getSources().size());
        assertEquals(10.0, request.getSources().get(0).getX(), 0.0);
        assertEquals(30.0, request.getSources().get(1).getX(), 0.0);
        assertEquals(50.0, request.getSources().get(2).getX(), 0.0);
        assertEquals(100.0f, request.getSources().get(0).getFlux(), 0.0f);
        assertEquals(50.0f, request.getSources().get(1).getFlux(), 0.0f);
        assertEquals(PlateSolverSettings.defaults(), request.getSolverSettings());
    }

    @Test
    public void customSettingsAreSnapshottedIntoTheAdaptedRequest() throws Exception {
        StarCaptureSession session = new StarCaptureSession();
        session.beginCapture(recipe());
        session.acceptIndication(successTerminal(0, "lens"));
        PlateSolverSettings settings =
                new PlateSolverSettings(64, 120, 45, 8, 2_500);

        SolveRequest request = StarSolveRequestAdapter
                .adapt(12, session.snapshot(), settings)
                .getRequest();

        assertEquals(settings, request.getSolverSettings());
    }

    @Test
    public void whitespaceOnlyLensIsMissingAfterCanonicalization() throws Exception {
        StarCaptureSession session = new StarCaptureSession();
        session.beginCapture(recipe());
        session.acceptIndication(successTerminal(0, "   "));

        StarSolveRequestAdapter.Adapted adapted =
                StarSolveRequestAdapter.adapt(8, session.snapshot());

        assertTrue(adapted.isLensMetadataMissing());
        assertEquals("", adapted.getRequest().getLensName());
    }

    @Test
    public void solveRequestTrimsEdgeSpacesButStillRejectsControls() {
        SolveRequest request = new SolveRequest(
                9,
                45.0,
                "  M.ZUIKO 45mm  ",
                Collections.singletonList(new SolveRequest.Source(10, 20, 30)));
        assertEquals("M.ZUIKO 45mm", request.getLensName());

        try {
            new SolveRequest(10, 45.0, "M.ZUIKO\n45mm", Collections.emptyList());
            throw new AssertionError("control character was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("lens name"));
        }

        try {
            new SolveRequest(11, 45.0, "123456789012345678901234567890123", Collections.emptyList());
            throw new AssertionError("overlong lens name was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("lens name"));
        }

        try {
            new SolveRequest(12, 45.0, "M.ZUIKO \u2122", Collections.emptyList());
            throw new AssertionError("non-ASCII lens name was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("lens name"));
        }
    }

    private static byte[] peaks(int[] x, int[] y, long[] flux) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x81);
        for (int index = 0; index < flux.length; index++) {
            writeUnsignedShort(output, x[index]);
            writeUnsignedShort(output, y[index]);
            writeUnsignedWord(output, flux[index]);
        }
        return output.toByteArray();
    }

    private static byte[] successTerminal(int total, String lensName) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x82);
        output.write(0);
        writeUnsignedShort(output, total);
        writeUnsignedShort(output, 1);
        writeUnsignedShort(output, 2);
        writeUnsignedShort(output, 3);
        writeUnsignedWord(output, Float.floatToRawIntBits(45.0f) & 0xffff_ffffL);
        byte[] lens = lensName.getBytes(StandardCharsets.US_ASCII);
        output.write(lens.length);
        output.write(lens, 0, lens.length);
        return output.toByteArray();
    }

    private static void writeUnsignedShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void writeUnsignedWord(ByteArrayOutputStream output, long value) {
        output.write((int) value & 0xff);
        output.write((int) (value >>> 8) & 0xff);
        output.write((int) (value >>> 16) & 0xff);
        output.write((int) (value >>> 24) & 0xff);
    }

    private static ExposureCatalogs.Selection recipe() {
        return new ExposureCatalogs.Selection("1/30", "F4.0", "800");
    }
}
