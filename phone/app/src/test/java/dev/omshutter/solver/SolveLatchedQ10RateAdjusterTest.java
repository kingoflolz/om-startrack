package dev.omshutter.solver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.omshutter.protocol.StarTelemetry;
import java.time.Instant;
import org.junit.Test;

public final class SolveLatchedQ10RateAdjusterTest {
    private static final Instant J2000 = Instant.parse("2000-01-01T12:00:00Z");
    private static final Instant OBSERVATION = Instant.parse("2026-09-12T00:00:00Z");
    private static final int QUALIFIED_FLAGS =
            StarTelemetry.IBIS_FLAG_SOURCE_MAPPED
                    | StarTelemetry.IBIS_FLAG_MIRROR_ENABLED
                    | StarTelemetry.IBIS_FLAG_DOUBLE_READ_STABLE;

    @Test
    public void zeroPlanarDeltaExactlyMatchesExistingCorrectionAndIgnoresPureRoll() {
        CorrectionAttitude attitude = CorrectionAttitude.of(123.0, 35.0, -62.0);
        StarTelemetry.IbisSample latched = sample(7, 231, -99, -700);
        StarTelemetry.IbisSample latest = sample(8, 231, -99, 700);

        SolveLatchedQ10RateAdjuster.Result result =
                SolveLatchedQ10RateAdjuster.adjust(
                        attitude, 0.65, latched, latest, 50.0,
                        SolveLatchedQ10RateAdjuster.DEFAULT_AXIS_MAPPING, OBSERVATION);

        assertTrue(result.isApplied());
        assertEquals(SolveLatchedQ10RateAdjuster.Status.APPLIED, result.getStatus());
        assertEquals(
                SiderealRateCorrection.fromEquatorialAttitude(
                        attitude.getRightAscensionDeg(),
                        attitude.getDeclinationDeg(),
                        attitude.getRollDeg(),
                        0.65, OBSERVATION),
                result.getCorrection());
    }

    @Test
    public void directMappingLocksGnomonicImageAxisSigns() {
        CorrectionAttitude northPole = CorrectionAttitude.of(0.0, 90.0, 0.0);
        CorrectionAttitude northAtImageRight = CorrectionAttitude.of(0.0, 0.0, 90.0);
        CorrectionAttitude northAtImageUp = CorrectionAttitude.of(0.0, 0.0, 0.0);
        StarTelemetry.IbisSample zero = sample(0, 0, 0, 0);

        // At the north pole R=-K, so +image-right u contributes R*u to pitch.
        assertCounts(
                adjust(
                        northPole, 1.0, zero, sample(1, 512, 0, 0), 15.0),
                0, -15, -729);
        // The same R term maps +image-down v into yaw.
        assertCounts(
                adjust(
                        northPole, 1.0, zero, sample(1, 0, 512, 0), 15.0),
                -15, 0, -729);
        // The R' half terms retain their specified u/v signs away from the pole.
        assertCounts(
                adjust(
                        northAtImageRight, 1.0, zero, sample(1, 512, 0, 0), 15.0),
                0, 729, -7);
        assertCounts(
                adjust(
                        northAtImageUp, 1.0, zero, sample(1, 0, 512, 0), 15.0),
                -729, 0, 7);
    }

    @Test
    public void mixedPlanarDeltaRequiresEnvelopeAndMatchesFormulaInsideIt() {
        CorrectionAttitude attitude = CorrectionAttitude.of(10.0, 25.0, 37.0);
        StarTelemetry.IbisSample solveLatched = sample(0, 0, 0, -400);
        StarTelemetry.IbisSample latest = sample(1, 700, -500, 900);

        // At 12 mm this old vector has hypot(u,v)=0.042004 and is conservatively unavailable.
        assertUnavailable(adjust(
                attitude, 1.0, solveLatched, latest, 12.0));

        // At 30 mm, u=0.013671875 and v=-0.009765625. Applying M(u,v) to the
        // dec=25, PA=37 base gives (-524.8463, 393.4877, -313.4742).
        SolveLatchedQ10RateAdjuster.Result result = adjust(
                attitude, 1.0, solveLatched, latest, 30.0);

        assertCounts(result, -525, 393, -313);
    }

    @Test
    public void customSignedPermutationChangesOnlyTheDeclaredMapping() {
        SolveLatchedQ10RateAdjuster.AxisMapping mapping =
                new SolveLatchedQ10RateAdjuster.AxisMapping(
                        SolveLatchedQ10RateAdjuster.PlanarLane.RAW_Y,
                        -1,
                        SolveLatchedQ10RateAdjuster.PlanarLane.RAW_X,
                        1);
        CorrectionAttitude attitude = CorrectionAttitude.of(0.0, 0.0, 90.0);
        StarTelemetry.IbisSample zero = sample(0, 0, 0, 0);

        SolveLatchedQ10RateAdjuster.Result custom = adjust(
                attitude, 1.0, zero, sample(1, 512, -256, 512), 20.0, mapping);
        SolveLatchedQ10RateAdjuster.Result explicitDirect = adjust(
                attitude,
                1.0,
                zero,
                sample(1, 256, 512, -512),
                20.0,
                SolveLatchedQ10RateAdjuster.DEFAULT_AXIS_MAPPING);

        assertTrue(custom.isApplied());
        assertEquals(explicitDirect.getCorrection(), custom.getCorrection());
    }

    @Test
    public void deltaIsSolveRelativeAndSequenceAndObservedChangeDoNotAffectIt() {
        CorrectionAttitude attitude = CorrectionAttitude.of(12.0, -20.0, 37.0);
        StarTelemetry.IbisSample solveLatched = sample(100, 300, -200, 50);
        StarTelemetry.IbisSample latest = sampleWithFlags(
                QUALIFIED_FLAGS | StarTelemetry.IBIS_FLAG_OBSERVED_CHANGE_SINCE_ENABLE,
                65535,
                420,
                -230,
                75);

        SolveLatchedQ10RateAdjuster.Result fromOffset = adjust(
                attitude, 0.4, solveLatched, latest, 75.0);
        SolveLatchedQ10RateAdjuster.Result fromZero = adjust(
                attitude, 0.4, sample(0, 0, 0, 0), sample(1, 120, -30, 25), 75.0);

        assertEquals(fromZero.getCorrection(), fromOffset.getCorrection());
    }

    @Test
    public void repeatedAndIntermediateEvaluationsCannotCreatePathDrift() {
        CorrectionAttitude attitude = CorrectionAttitude.of(12.0, -20.0, 37.0);
        StarTelemetry.IbisSample solveLatched = sample(100, -400, 300, -200);
        StarTelemetry.IbisSample target = sample(104, 500, -600, 700);
        SiderealRateCorrection expected = adjust(
                attitude, 0.8, solveLatched, target, 50.0).getCorrection();

        adjust(
                attitude, 0.8, solveLatched, sample(101, -100, 200, 0), 50.0);
        adjust(
                attitude, 0.8, solveLatched, sample(102, 200, -100, 200), 50.0);
        adjust(
                attitude, 0.8, solveLatched, sample(103, -700, 700, -700), 50.0);

        assertEquals(
                expected,
                adjust(
                        attitude, 0.8, solveLatched, target, 50.0).getCorrection());
    }

    @Test
    public void conservativeLaneAndRadialBoundariesAreInclusiveOnlyAsDocumented() {
        CorrectionAttitude attitude = CorrectionAttitude.of(0.0, 45.0, 30.0);
        StarTelemetry.IbisSample laneEdge = sample(0, 959, -959, 959);
        assertTrue(adjust(
                attitude, 1.0, laneEdge, laneEdge, 50.0).isApplied());

        // 512 * (0.6/1024) / 15 is exactly the accepted radial boundary 0.02.
        StarTelemetry.IbisSample zero = sample(0, 0, 0, 0);
        assertTrue(adjust(
                attitude, 1.0, zero, sample(1, 512, 0, 0), 15.0).isApplied());
        assertUnavailable(adjust(
                attitude, 1.0, zero, sample(1, 513, 0, 0), 15.0));

        for (int held : new int[]{-960, 960}) {
            assertUnavailable(adjust(
                    attitude, 1.0, sample(0, held, 0, 0), zero, 50.0));
            assertUnavailable(adjust(
                    attitude, 1.0, zero, sample(1, 0, held, 0), 50.0));
            assertUnavailable(adjust(
                    attitude, 1.0, sample(0, 0, 0, held), zero, 50.0));
            assertUnavailable(adjust(
                    attitude, 1.0, zero, sample(1, 0, 0, held), 50.0));
        }
    }

    @Test
    public void unavailableForNullInvalidFactorFocalFlagsOrSaturation() {
        CorrectionAttitude attitude = CorrectionAttitude.of(0.0, 0.0, 0.0);
        StarTelemetry.IbisSample qualified = sample(0, 0, 0, 0);
        StarTelemetry.IbisSample unqualified = sampleWithFlags(
                StarTelemetry.IBIS_FLAG_SOURCE_MAPPED
                        | StarTelemetry.IBIS_FLAG_MIRROR_ENABLED,
                0,
                0,
                0,
                0);

        assertUnavailable(adjust(
                null, 1.0, qualified, qualified, 50.0));
        assertUnavailable(adjust(
                attitude, -0.01, qualified, qualified, 50.0));
        assertUnavailable(adjust(
                attitude, 1.01, qualified, qualified, 50.0));
        assertUnavailable(adjust(
                attitude, Double.NaN, qualified, qualified, 50.0));
        assertUnavailable(adjust(
                attitude, Double.POSITIVE_INFINITY, qualified, qualified, 50.0));
        assertUnavailable(adjust(
                attitude, 1.0, qualified, qualified, 0.0));
        assertUnavailable(adjust(
                attitude, 1.0, qualified, qualified, -1.0));
        assertUnavailable(adjust(
                attitude, 1.0, qualified, qualified, Double.NaN));
        assertUnavailable(adjust(
                attitude, 1.0, qualified, qualified, Double.POSITIVE_INFINITY));
        assertUnavailable(adjust(
                attitude, 1.0, null, qualified, 50.0));
        assertUnavailable(adjust(
                attitude, 1.0, qualified, null, 50.0));
        assertUnavailable(adjust(
                attitude, 1.0, unqualified, qualified, 50.0));
        assertUnavailable(adjust(
                attitude, 1.0, qualified, unqualified, 50.0));

    }

    @Test
    public void factorZeroIsAValidAppliedZeroRateAndResultIsDeterministic() {
        CorrectionAttitude attitude = CorrectionAttitude.of(77.0, 43.0, -28.0);
        StarTelemetry.IbisSample solveLatched = sample(20, -300, 200, -100);
        StarTelemetry.IbisSample latest = sample(21, 200, -150, 400);

        SolveLatchedQ10RateAdjuster.Result first = adjust(
                attitude, 0.0, solveLatched, latest, 25.0);
        SolveLatchedQ10RateAdjuster.Result second = adjust(
                attitude, 0.0, solveLatched, latest, 25.0);

        assertCounts(first, 0, 0, 0);
        assertEquals(first.getCorrection(), second.getCorrection());
    }

    @Test
    public void axisMappingRejectsNullDuplicateLanesAndNonUnitSigns() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SolveLatchedQ10RateAdjuster.AxisMapping(
                        null, 1, SolveLatchedQ10RateAdjuster.PlanarLane.RAW_Y, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SolveLatchedQ10RateAdjuster.AxisMapping(
                        SolveLatchedQ10RateAdjuster.PlanarLane.RAW_X,
                        1,
                        SolveLatchedQ10RateAdjuster.PlanarLane.RAW_X,
                        1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SolveLatchedQ10RateAdjuster.AxisMapping(
                        SolveLatchedQ10RateAdjuster.PlanarLane.RAW_X,
                        0,
                        SolveLatchedQ10RateAdjuster.PlanarLane.RAW_Y,
                        1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SolveLatchedQ10RateAdjuster.AxisMapping(
                        SolveLatchedQ10RateAdjuster.PlanarLane.RAW_X,
                        1,
                        SolveLatchedQ10RateAdjuster.PlanarLane.RAW_Y,
                        2));
    }

    @Test
    public void nonzeroPlanarDeltaUsesTheSameDatedPoleAsTheZeroDeltaBaseline() {
        CorrectionAttitude attitude = CorrectionAttitude.of(123.0, 35.0, -62.0);
        StarTelemetry.IbisSample latched = sample(20, -100, 200, 17);
        StarTelemetry.IbisSample latest = sample(21, 600, -300, -19);
        double factor = 0.65;
        double u = 700 * SolveLatchedQ10RateAdjuster.PLANAR_MM_PER_Q10_COUNT / 30.0;
        double v = -500 * SolveLatchedQ10RateAdjuster.PLANAR_MM_PER_Q10_COUNT / 30.0;
        SiderealRateCorrection.BodyAngularVelocity base =
                SiderealRateCorrection.bodyAngularVelocityFromEquatorialAttitude(
                        123.0, 35.0, -62.0, factor, OBSERVATION);
        double yaw = base.getYRadiansPerSecond();
        double pitch = base.getXRadiansPerSecond();
        double roll = -base.getZRadiansPerSecond();
        SiderealRateCorrection expected = SiderealRateCorrection.fromBodyAngularVelocity(
                pitch * (1 + v * v) - yaw * u * v + roll * u,
                yaw * (1 + u * u) - pitch * u * v + roll * v,
                -(roll - 0.5 * (pitch * u + yaw * v)));
        SolveLatchedQ10RateAdjuster.Result result = SolveLatchedQ10RateAdjuster.adjust(
                attitude, factor, latched, latest, 30.0,
                SolveLatchedQ10RateAdjuster.DEFAULT_AXIS_MAPPING, OBSERVATION);

        assertTrue(result.isApplied());
        assertEquals(expected, result.getCorrection());
        assertNotEquals(adjust(attitude, factor, latched, latest, 30.0).getCorrection(),
                result.getCorrection());

        // A small but nonzero displacement must not switch back to the old
        // RA-independent ICRS-Z formula after the zero-delta fast path.
        SiderealRateCorrection atZero = SolveLatchedQ10RateAdjuster.adjust(
                attitude, factor, latched, latched, 30.0,
                SolveLatchedQ10RateAdjuster.DEFAULT_AXIS_MAPPING, OBSERVATION).getCorrection();
        SiderealRateCorrection oneCount = SolveLatchedQ10RateAdjuster.adjust(
                attitude, factor, latched, sample(22, -99, 200, 17), 30.0,
                SolveLatchedQ10RateAdjuster.DEFAULT_AXIS_MAPPING, OBSERVATION).getCorrection();
        double difference = Math.hypot(Math.hypot(
                oneCount.getYawRadiansPerSecond() - atZero.getYawRadiansPerSecond(),
                oneCount.getPitchRadiansPerSecond() - atZero.getPitchRadiansPerSecond()),
                oneCount.getRollRadiansPerSecond() - atZero.getRollRadiansPerSecond());
        assertTrue(difference < 2.0e-9);
    }

    @Test
    public void suppliedContinuousBaselineSurvivesZeroAndNonzeroPlanarDelta() {
        double unit = SiderealRateCorrection.WIRE_Q16_UNIT_RAD_PER_SECOND;
        double x = 12_345.49 * unit;
        double y = -65_432.49 * unit;
        double z = 234_567.49 * unit;
        SiderealRateCorrection.BodyAngularVelocity baseline =
                SiderealRateCorrection.BodyAngularVelocity.of(x, y, z);
        StarTelemetry.IbisSample latched = sample(10, 0, 0, -400);
        SolveLatchedQ10RateAdjuster.AxisMapping mapping =
                SolveLatchedQ10RateAdjuster.DEFAULT_AXIS_MAPPING;

        SolveLatchedQ10RateAdjuster.Result zero = SolveLatchedQ10RateAdjuster.adjustBaseline(
                baseline, latched, sample(11, 0, 0, 400), 15.0, mapping);
        assertTrue(zero.isApplied());
        assertEquals(SiderealRateCorrection.fromBodyAngularVelocity(x, y, z), zero.getCorrection());
        assertEquals(x, zero.getBodyAngularVelocity().getXRadiansPerSecond(), 0.0);
        assertEquals(0.0, zero.getImageRightGnomonic(), 0.0);
        assertEquals(0.0, zero.getImageDownGnomonic(), 0.0);

        // For image-right u=0.02 and v=0, the body-space linear map is
        // x'=x-z*u, y'=y*(1+u*u), z'=z+x*u/2. These fractions are chosen
        // so quantizing the supplied baseline first changes two final lanes.
        double u = 0.02;
        SiderealRateCorrection expected = SiderealRateCorrection.fromBodyAngularVelocity(
                x - z * u, y * (1.0 + u * u), z + 0.5 * x * u);
        SolveLatchedQ10RateAdjuster.Result displaced = SolveLatchedQ10RateAdjuster.adjustBaseline(
                baseline, latched, sample(12, 512, 0, 0), 15.0, mapping);
        assertTrue(displaced.isApplied());
        assertEquals(expected, displaced.getCorrection());
        assertEquals(x - z * u,
                displaced.getBodyAngularVelocity().getXRadiansPerSecond(), 0.0);
        assertEquals(u, displaced.getImageRightGnomonic(), 0.0);
        assertEquals(0.0, displaced.getImageDownGnomonic(), 0.0);
        assertEquals(-65_459, displaced.getCorrection().getYawQ16());
        assertEquals(7_654, displaced.getCorrection().getPitchQ16());
        assertEquals(-234_691, displaced.getCorrection().getRollQ16());

        SiderealRateCorrection quantized = zero.getCorrection();
        double quantizedX = quantized.getPitchRadiansPerSecond();
        double quantizedY = quantized.getYawRadiansPerSecond();
        double quantizedZ = -quantized.getRollRadiansPerSecond();
        SiderealRateCorrection prematurelyQuantized = SiderealRateCorrection.fromBodyAngularVelocity(
                quantizedX - quantizedZ * u,
                quantizedY * (1.0 + u * u),
                quantizedZ + 0.5 * quantizedX * u);
        assertNotEquals(prematurelyQuantized, displaced.getCorrection());
    }

    private static SolveLatchedQ10RateAdjuster.Result adjust(
            CorrectionAttitude attitude,
            double factor,
            StarTelemetry.IbisSample latched,
            StarTelemetry.IbisSample latest,
            double focalLengthMm) {
        return adjust(attitude, factor, latched, latest, focalLengthMm,
                SolveLatchedQ10RateAdjuster.DEFAULT_AXIS_MAPPING);
    }

    private static SolveLatchedQ10RateAdjuster.Result adjust(
            CorrectionAttitude attitude,
            double factor,
            StarTelemetry.IbisSample latched,
            StarTelemetry.IbisSample latest,
            double focalLengthMm,
            SolveLatchedQ10RateAdjuster.AxisMapping mapping) {
        return SolveLatchedQ10RateAdjuster.adjust(
                attitude, factor, latched, latest, focalLengthMm, mapping, J2000);
    }

    private static StarTelemetry.IbisSample sample(
            int sequence, int rawX, int rawY, int rawRoll) {
        return sampleWithFlags(QUALIFIED_FLAGS, sequence, rawX, rawY, rawRoll);
    }

    private static StarTelemetry.IbisSample sampleWithFlags(
            int flags, int sequence, int rawX, int rawY, int rawRoll) {
        return new StarTelemetry.IbisSample(
                flags, sequence, rawX, rawY, rawRoll);
    }

    private static void assertUnavailable(SolveLatchedQ10RateAdjuster.Result result) {
        assertFalse(result.isApplied());
        assertEquals(SolveLatchedQ10RateAdjuster.Status.UNAVAILABLE, result.getStatus());
        assertNull(result.getCorrection());
    }

    private static void assertCounts(
            SolveLatchedQ10RateAdjuster.Result result,
            int expectedYaw,
            int expectedPitch,
            int expectedRoll) {
        assertTrue(result.isApplied());
        SiderealRateCorrection correction = result.getCorrection();
        assertEquals(expectedYaw, correction.getYawCounts());
        assertEquals(expectedPitch, correction.getPitchCounts());
        assertEquals(expectedRoll, correction.getRollCounts());
    }
}
