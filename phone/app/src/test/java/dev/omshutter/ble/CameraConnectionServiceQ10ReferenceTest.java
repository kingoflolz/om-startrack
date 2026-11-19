package dev.omshutter.ble;

import static org.junit.Assert.assertSame;

import dev.omshutter.protocol.StarTelemetry;

import org.junit.Test;

public final class CameraConnectionServiceQ10ReferenceTest {
    private static final int QUALIFIED_FLAGS =
            StarTelemetry.IBIS_FLAG_SOURCE_MAPPED
                    | StarTelemetry.IBIS_FLAG_MIRROR_ENABLED
                    | StarTelemetry.IBIS_FLAG_DOUBLE_READ_STABLE;

    @Test
    public void sameGenerationCaptureStartWinsAndDifferentGenerationCannotLeak() {
        StarTelemetry.IbisSample captureStart = sample(10, 20, -30, 40);
        StarTelemetry.IbisSample terminal = sample(11, 200, -300, 400);

        assertSame(
                captureStart,
                CameraConnectionService.selectSolveReferenceQ10(
                        42L, 42L, captureStart, terminal));
        assertSame(
                terminal,
                CameraConnectionService.selectSolveReferenceQ10(
                        43L, 42L, captureStart, terminal));
    }

    @Test
    public void unqualifiedCaptureStartFallsBackToQualifiedTerminal() {
        StarTelemetry.IbisSample captureStart =
                new StarTelemetry.IbisSample(0, 10, 20, -30, 40);
        StarTelemetry.IbisSample terminal = sample(11, 200, -300, 400);

        assertSame(
                terminal,
                CameraConnectionService.selectSolveReferenceQ10(
                        42L, 42L, captureStart, terminal));
    }

    private static StarTelemetry.IbisSample sample(
            int sequence, int rawX, int rawY, int rawRoll) {
        return new StarTelemetry.IbisSample(
                QUALIFIED_FLAGS, sequence, rawX, rawY, rawRoll);
    }
}
