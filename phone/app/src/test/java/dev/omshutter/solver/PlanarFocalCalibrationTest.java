package dev.omshutter.solver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import org.junit.Test;

public final class PlanarFocalCalibrationTest {
    @Test
    public void largerSolvedFocalRequiresProportionallyLargerPlanarCommand() {
        PlanarFocalCalibration calibration = PlanarFocalCalibration.of(300, 312.5);
        assertEquals(300, calibration.getSuppliedFocalLengthMm(), 0);
        assertEquals(312.5, calibration.getSolvedFocalLengthMm(), 0);
        assertEquals(1 / 0.96, calibration.getScale(), 0);
        double x = 5.1e-5, y = -2.3e-5, z = 4.7e-5;
        SiderealRateCorrection.BodyAngularVelocity corrected = calibration.apply(
                SiderealRateCorrection.BodyAngularVelocity.of(x, y, z));
        // Native uses the supplied focal to convert angular command into sensor travel.
        assertEquals(312.5 * x, 300 * corrected.getXRadiansPerSecond(), 1e-16);
        assertEquals(312.5 * y, 300 * corrected.getYRadiansPerSecond(), 1e-16);
        assertEquals(z, corrected.getZRadiansPerSecond(), 0);
    }

    @Test
    public void smallerSolvedFocalReducesPlanarRatesWithoutChangingRoll() {
        PlanarFocalCalibration calibration = PlanarFocalCalibration.of(300, 285);
        assertArrayEquals(new double[]{9.5e-6, -1.9e-5, -3e-5}, calibration.apply(
                SiderealRateCorrection.BodyAngularVelocity.of(1e-5, -2e-5, -3e-5)).toArray(), 1e-20);
    }

    @Test
    public void q10OpticalFocalUsesSameRatioWithoutMutatingNominalMetadata() {
        PlanarFocalCalibration calibration = PlanarFocalCalibration.of(300, 312.5);
        assertEquals(312.5, calibration.effectiveFocalLengthMm(300), 0);
        assertEquals(156.25, calibration.effectiveFocalLengthMm(150), 0);
        assertEquals(300, calibration.getSuppliedFocalLengthMm(), 0);
    }

    @Test
    public void continuousFractionsAreScaledBeforeFinalQuantization() {
        double unit = SiderealRateCorrection.WIRE_Q16_UNIT_RAD_PER_SECOND;
        SiderealRateCorrection.BodyAngularVelocity original =
                SiderealRateCorrection.BodyAngularVelocity.of(0.49 * unit, -0.49 * unit, 0.49 * unit);
        SiderealRateCorrection output = PlanarFocalCalibration.of(300, 330).apply(original)
                .toRateCorrection();
        assertEquals(1, output.getPitchQ16());
        assertEquals(-1, output.getYawQ16());
        assertEquals(0, output.getRollQ16());
    }

    @Test
    public void nativeRateLimitsAreCheckedBeforeClampingIncludingUnscaledRoll() {
        double limit = SiderealRateCorrection.MAX_WIRE_COUNT
                * SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND;
        PlanarFocalCalibration identity = PlanarFocalCalibration.of(300, 300);
        assertArrayEquals(new double[]{limit, -limit, limit}, identity.apply(
                SiderealRateCorrection.BodyAngularVelocity.of(limit, -limit, limit)).toArray(), 0);
        double above = Math.nextUp(limit);
        for (double[] excessive : new double[][]{{above, 0, 0}, {0, -above, 0}, {0, 0, above}}) {
            assertThrows(IllegalArgumentException.class, () -> identity.apply(
                    SiderealRateCorrection.BodyAngularVelocity.of(
                            excessive[0], excessive[1], excessive[2])));
        }
        assertThrows(IllegalArgumentException.class, () -> PlanarFocalCalibration.of(300, 600)
                .apply(SiderealRateCorrection.BodyAngularVelocity.of(limit, 0, 0)));
    }

    @Test
    public void malformedFocalsAndOverflowAreRejected() {
        for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> PlanarFocalCalibration.of(invalid, 300));
            assertThrows(IllegalArgumentException.class, () -> PlanarFocalCalibration.of(300, invalid));
            assertThrows(IllegalArgumentException.class, () -> PlanarFocalCalibration.of(300, 312.5)
                    .effectiveFocalLengthMm(invalid));
        }
        assertThrows(IllegalArgumentException.class,
                () -> PlanarFocalCalibration.of(Double.MIN_VALUE, Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> PlanarFocalCalibration.of(1, 2).effectiveFocalLengthMm(Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> PlanarFocalCalibration.of(300, 300).apply(null));
    }
}
