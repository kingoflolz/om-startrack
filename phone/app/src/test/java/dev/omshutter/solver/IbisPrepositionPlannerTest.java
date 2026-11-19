package dev.omshutter.solver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class IbisPrepositionPlannerTest {
    @Test
    public void positiveWireYawStartsPositiveXOppositeTheExposureSweep() {
        IbisPrepositionPlanner.Plan plan = plan(100.0f, 1_000, 0, 0, 20.0);

        // 100 mm * 1e-4 rad/s * 3000 target counts/mm = approximately 30 counts/s.
        // Twenty seconds therefore needs a +300-count start to center the negative sweep.
        assertTrue(plan.isAvailable());
        assertEquals(300, plan.getLogicalX());
        assertEquals(0, plan.getLogicalY());
        assertEquals(0, plan.getLogicalRoll());
        assertEquals(20.0, plan.getPlannedSeconds(), 0.0);
        assertFalse(plan.isExposureClipped());
    }

    @Test
    public void pitchFeedsLogicalYAndRollPreservesWireSign() {
        IbisPrepositionPlanner.Plan pitch = plan(100.0f, 0, -1_000, 0, 20.0);
        assertEquals(0, pitch.getLogicalX());
        assertEquals(-300, pitch.getLogicalY());
        assertEquals(0, pitch.getLogicalRoll());

        IbisPrepositionPlanner.Plan roll = plan(100.0f, 0, 0, 1_000, 20.0);
        // 0.001 rad per half exposure * 180/pi * 8192 counts/degree.
        assertEquals(0, roll.getLogicalX());
        assertEquals(0, roll.getLogicalY());
        assertEquals(469, roll.getLogicalRoll());
    }

    @Test
    public void reversingAllRatesExactlyReversesThePlan() {
        IbisPrepositionPlanner.Plan forward = plan(75.0f, 500, -333, 217, 15.0);
        IbisPrepositionPlanner.Plan reverse = plan(75.0f, -500, 333, -217, 15.0);

        assertEquals(-forward.getLogicalX(), reverse.getLogicalX());
        assertEquals(-forward.getLogicalY(), reverse.getLogicalY());
        assertEquals(-forward.getLogicalRoll(), reverse.getLogicalRoll());
        assertEquals(forward.getMaximumSeconds(), reverse.getMaximumSeconds(), 0.0);
    }

    @Test
    public void fullSweepAlmostDoublesTheSameEnvelopeCenteredTime() {
        IbisPrepositionPlanner.Plan plan = plan(100.0f, 1_000, 0, 0, 1_000.0);

        assertTrue(plan.isExposureClipped());
        assertEquals(2_494, plan.getLogicalX());
        assertEquals(plan.getMaximumSeconds(), plan.getPlannedSeconds(), 0.0);
        assertEquals(166.3, plan.getMaximumSeconds(), 0.1);
        // Requested exposure is an input only: the planner must not report the entire 1000 s as
        // covered, nor place the actuator beyond its reserved envelope to try to cover it.
        assertTrue(plan.getPlannedSeconds() < 1_000.0);
    }

    @Test
    public void combinedPlanarAndRollSweepRespectsBothEndpointEnvelopes() {
        for (int yaw : new int[]{-2_917, -1_000, 0, 729, 2_917}) {
            for (int pitch : new int[]{-2_917, 0, 1_000, 2_917}) {
                for (int roll : new int[]{-2_917, 0, 2_917}) {
                    if (yaw == 0 && pitch == 0 && roll == 0) continue;
                    IbisPrepositionPlanner.Plan plan = plan(300.0f, yaw, pitch, roll, 1.0e9);
                    assertTrue(plan.isAvailable());
                    assertTrue(plan.isExposureClipped());
                    assertTrue(Math.hypot(plan.getLogicalX(), plan.getLogicalY())
                            <= IbisPrepositionPlanner.MAX_PLANAR_RADIUS_COUNTS);
                    assertTrue(Math.abs(plan.getLogicalRoll())
                            <= IbisPrepositionPlanner.MAX_ROLL_COUNTS);

                    // Advance the integer starting pose using the unrounded native sweep. Both
                    // endpoints must fit, even when rounding moves the start toward the center.
                    double seconds = plan.getPlannedSeconds();
                    double gain = -(23 * 9_600 + ((30_694 * 9_600 + 32_768) >> 16));
                    double planarPerCount = Math.scalb(gain * 2_000.0 * 1_757.0, -16 - 27);
                    double rollPerCount = Math.scalb(-192_253.0 * 2_000.0 * 4_295.0, -27 - 18);
                    double endX = plan.getLogicalX() + yaw * planarPerCount * seconds;
                    double endY = plan.getLogicalY() + pitch * planarPerCount * seconds;
                    double endRoll = plan.getLogicalRoll() + roll * rollPerCount * seconds;
                    assertTrue(Math.hypot(endX, endY)
                            <= IbisPrepositionPlanner.MAX_PLANAR_RADIUS_COUNTS);
                    assertTrue(Math.abs(endRoll) <= IbisPrepositionPlanner.MAX_ROLL_COUNTS);
                }
            }
        }
    }

    @Test
    public void rollCanBeTheLimitingAxisAtShortFocalLength() {
        IbisPrepositionPlanner.Plan plan = plan(7.0f, 1_000, 0, 2_917, 1_000.0);
        assertEquals(5_218, plan.getLogicalRoll());
        assertTrue(plan.getLogicalX() < 100);
        assertEquals(76.2, plan.getMaximumSeconds(), 0.2);
    }

    @Test
    public void focalIsQuantizedLikeTheCameraAndRollIsFocalIndependent() {
        IbisPrepositionPlanner.Plan a = plan(50.0f, 1_000, 0, 1_000, 20.0);
        IbisPrepositionPlanner.Plan b = plan(50.03f, 1_000, 0, 1_000, 20.0);
        IbisPrepositionPlanner.Plan c = plan(100.0f, 1_000, 0, 1_000, 20.0);
        assertEquals(a.getLogicalX(), b.getLogicalX());
        assertEquals(a.getMaximumSeconds(), b.getMaximumSeconds(), 0.0);
        assertEquals(a.getLogicalX() * 2, c.getLogicalX());
        assertEquals(a.getLogicalRoll(), c.getLogicalRoll());
    }

    @Test
    public void fractionalWireRatesAreNotLostThroughLegacyCounts() {
        SiderealRateCorrection rate = rate(0.25, 0.0, 0.0);
        assertEquals(0, rate.getYawCounts());
        IbisPrepositionPlanner.Plan plan = IbisPrepositionPlanner.plan(100.0f, rate, 1_000.0);
        assertTrue(plan.isAvailable());
        assertEquals(4, plan.getLogicalX());
        assertEquals(0, plan.getLogicalY());
        assertEquals(0, plan.getLogicalRoll());
    }

    @Test
    public void invalidInputsAndZeroRatesDoNotArmMovement() {
        SiderealRateCorrection rate = rate(1_000, 0, 0);
        for (float focal : new float[]{0.0f, -1.0f, Float.NaN, Float.POSITIVE_INFINITY,
                0.03f, 981.0f}) {
            assertUnavailable(IbisPrepositionPlanner.plan(focal, rate, 20.0));
        }
        for (double seconds : new double[]{0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertUnavailable(IbisPrepositionPlanner.plan(50.0f, rate, seconds));
        }
        assertUnavailable(IbisPrepositionPlanner.plan(50.0f, null, 20.0));
        assertUnavailable(plan(50.0f, 0, 0, 0, 20.0));
        assertTrue(plan(980.25f, 1_000, 0, 0, 20.0).isAvailable());
    }

    @Test
    public void qualifiedCoupledPlanUsesNearlyTheFullCircleForTranslation() {
        IbisPrepositionPlanner.Limits limits = IbisPrepositionPlanner.Limits.of(
                4_050, 4_050, 21_299, true);
        IbisPrepositionPlanner.Plan axis = IbisPrepositionPlanner.plan(
                100.0f, rate(1_000, 0, 0), 1.0e9, limits);
        IbisPrepositionPlanner.Plan diagonal = IbisPrepositionPlanner.plan(
                100.0f, rate(1_000, 1_000, 0), 1.0e9, limits);

        assertEquals(2_494, axis.getLogicalX());
        assertEquals(0, axis.getLogicalRoll());
        assertTrue(axis.getMaximumSeconds() > 166.0);
        assertEquals(axis.getMaximumSeconds() / Math.sqrt(2.0),
                diagonal.getMaximumSeconds(), 1e-12);
        assertTrue(Math.hypot(diagonal.getLogicalX(), diagonal.getLogicalY()) > 2_490.0);
        assertWithinActiveEnvelope(axis.getLogicalX(), axis.getLogicalY(), axis.getLogicalRoll(),
                limits);
        assertWithinActiveEnvelope(diagonal.getLogicalX(), diagonal.getLogicalY(),
                diagonal.getLogicalRoll(), limits);
    }

    @Test
    public void combinedRollLimitsEachPlanarDirectionUsingItsOwnCoupledSlope() {
        IbisPrepositionPlanner.Limits limits = IbisPrepositionPlanner.Limits.of(
                4_050, 4_050, 21_299, true);
        IbisPrepositionPlanner.Plan x = IbisPrepositionPlanner.plan(
                100.0f, rate(1_000, 0, 2_917), 1.0e9, limits);
        IbisPrepositionPlanner.Plan y = IbisPrepositionPlanner.plan(
                100.0f, rate(0, 1_000, 2_917), 1.0e9, limits);

        assertTrue(x.getLogicalX() < 2_490);
        assertEquals(0.98 * 2_700, x.getLogicalX() + 1_628.0 / 32_768 * x.getLogicalRoll(),
                1.1);
        assertEquals(0.98 * 2_700, y.getLogicalY() + 1_571.0 / 32_768 * y.getLogicalRoll(),
                1.1);
        assertTrue(y.getMaximumSeconds() > x.getMaximumSeconds());
        assertWithinActiveEnvelope(x.getLogicalX(), x.getLogicalY(), x.getLogicalRoll(), limits);
        assertWithinActiveEnvelope(y.getLogicalX(), y.getLogicalY(), y.getLogicalRoll(), limits);
    }

    @Test
    public void actualAxisAndRollLimitsOverrideCompiledMaxima() {
        IbisPrepositionPlanner.Plan axis = IbisPrepositionPlanner.plan(
                100.0f, rate(1_000, 0, 0), 1.0e9,
                IbisPrepositionPlanner.Limits.of(1_200, 1_800, 3_276, true));
        assertEquals(0.98 * 1_200, axis.getLogicalX(), 1.0);

        IbisPrepositionPlanner.Plan smallRoll = IbisPrepositionPlanner.plan(
                7.0f, rate(1_000, 0, 2_917), 1.0e9,
                IbisPrepositionPlanner.Limits.of(4_050, 4_050, 3_276, true));
        IbisPrepositionPlanner.Plan largeRoll = IbisPrepositionPlanner.plan(
                7.0f, rate(1_000, 0, 2_917), 1.0e9,
                IbisPrepositionPlanner.Limits.of(4_050, 4_050, 21_299, true));
        assertEquals(0.98 * 3_276, smallRoll.getLogicalRoll(), 1.0);
        assertEquals(0.98 * 21_299, largeRoll.getLogicalRoll(), 1.0);
        assertTrue(largeRoll.getMaximumSeconds() > 6.4 * smallRoll.getMaximumSeconds());
        // The default uses the actual still-capture roll envelope, not the larger mode-2 value.
        assertEquals(5_218, plan(7.0f, 1_000, 0, 2_917, 1.0e9).getLogicalRoll());
    }

    @Test
    public void defaultMatchesExplicitStillLimitsWithoutUsingTheModeTwoRollMaximum() {
        IbisPrepositionPlanner.Limits still = IbisPrepositionPlanner.Limits.of(
                2_546, 2_546, 5_325, true);
        IbisPrepositionPlanner.Plan translation = IbisPrepositionPlanner.plan(
                100.0f, rate(1_000, 0, 0), 1.0e9, still);
        IbisPrepositionPlanner.Plan roll = IbisPrepositionPlanner.plan(
                7.0f, rate(1_000, 0, 2_917), 1.0e9, still);

        assertEquals(2_494, translation.getLogicalX());
        assertEquals(5_218, roll.getLogicalRoll());
        assertEquals(translation.getMaximumSeconds(),
                plan(100.0f, 1_000, 0, 0, 1.0e9).getMaximumSeconds(), 0.0);
        assertEquals(roll.getMaximumSeconds(),
                plan(7.0f, 1_000, 0, 2_917, 1.0e9).getMaximumSeconds(), 0.0);
        assertWithinActiveEnvelope(translation.getLogicalX(), translation.getLogicalY(),
                translation.getLogicalRoll(), still);
        assertWithinActiveEnvelope(roll.getLogicalX(), roll.getLogicalY(), roll.getLogicalRoll(),
                still);
    }

    @Test
    public void independentBuilderUsesItsAxisBoxWithoutCoupledRestrictions() {
        IbisPrepositionPlanner.Limits limits = IbisPrepositionPlanner.Limits.of(
                4_050, 3_000, 11_468, false);
        IbisPrepositionPlanner.Plan plan = IbisPrepositionPlanner.plan(
                100.0f, rate(1_000, 1_000, 1_000), 1.0e9, limits);

        assertTrue(plan.isAvailable());
        assertEquals(0.98 * 3_000, plan.getLogicalY(), 1.0);
        assertTrue(Math.hypot(plan.getLogicalX(), plan.getLogicalY()) > 2_546);
        assertTrue(plan.getLogicalX() + 1_628.0 / 32_768 * plan.getLogicalRoll() > 2_700);
        assertWithinActiveEnvelope(plan.getLogicalX(), plan.getLogicalY(), plan.getLogicalRoll(),
                limits);
    }

    @Test
    public void roundedStartAndEntireNativeSweepStayInsideQualifiedVolume() {
        IbisPrepositionPlanner.Limits[] profiles = {
                IbisPrepositionPlanner.Limits.of(2_546, 2_546, 5_325, true),
                IbisPrepositionPlanner.Limits.of(4_050, 4_050, 21_299, true),
                IbisPrepositionPlanner.Limits.of(1_800, 1_800, 3_276, true),
                IbisPrepositionPlanner.Limits.of(1_647, 1_647, 11_468, true),
                IbisPrepositionPlanner.Limits.of(4_050, 3_000, 5_325, false)
        };
        for (IbisPrepositionPlanner.Limits limits : profiles) {
            for (double yaw : new double[]{-2_917, -333.25, 0, 729, 2_917}) {
                for (double pitch : new double[]{-2_917, 0, 1_000.75, 2_917}) {
                    for (double roll : new double[]{-2_917, 0, 2_917}) {
                        if (yaw == 0 && pitch == 0 && roll == 0) continue;
                        SiderealRateCorrection rate = rate(yaw, pitch, roll);
                        IbisPrepositionPlanner.Plan plan = IbisPrepositionPlanner.plan(
                                300.0f, rate, 1.0e9, limits);
                        assertTrue(plan.isAvailable());
                        assertTrue(plan.isExposureClipped());
                        double[] speed = nativeSpeedAt300Mm(rate);
                        for (double fraction : new double[]{0, 0.25, 0.5, 0.75, 1}) {
                            double elapsed = fraction * plan.getPlannedSeconds();
                            assertWithinActiveEnvelope(plan.getLogicalX() + speed[0] * elapsed,
                                    plan.getLogicalY() + speed[1] * elapsed,
                                    plan.getLogicalRoll() + speed[2] * elapsed, limits);
                        }
                        // Reversing the trajectory keeps its full available duration and volume.
                        IbisPrepositionPlanner.Plan reverse = IbisPrepositionPlanner.plan(
                                300.0f, rate(-yaw, -pitch, -roll), 1.0e9, limits);
                        assertEquals(plan.getMaximumSeconds(), reverse.getMaximumSeconds(), 0.0);
                        assertEquals(-plan.getLogicalX(), reverse.getLogicalX());
                        assertEquals(-plan.getLogicalY(), reverse.getLogicalY());
                        assertEquals(-plan.getLogicalRoll(), reverse.getLogicalRoll());
                    }
                }
            }
        }
    }

    @Test
    public void unknownOrInactiveLimitsCannotArmMovement() {
        assertUnavailable(IbisPrepositionPlanner.plan(100.0f, rate(1_000, 0, 0), 20.0, null));
        for (int invalid : new int[]{-1, 0, 32_768, Integer.MAX_VALUE}) {
            IbisPrepositionPlanner.Limits[] invalidLimits = {
                    IbisPrepositionPlanner.Limits.of(invalid, 1_800, 3_276, true),
                    IbisPrepositionPlanner.Limits.of(1_800, invalid, 3_276, false),
                    IbisPrepositionPlanner.Limits.of(1_800, 1_800, invalid, true)
            };
            for (IbisPrepositionPlanner.Limits limits : invalidLimits) {
                assertFalse(limits.isAvailable());
                assertUnavailable(IbisPrepositionPlanner.plan(
                        100.0f, rate(1_000, 0, 0), 20.0, limits));
            }
        }
    }

    private static double[] nativeSpeedAt300Mm(SiderealRateCorrection rate) {
        double gain = -(23 * 9_600 + ((30_694 * 9_600 + 32_768) >> 16));
        double planar = Math.scalb(gain * 2_000.0 * 1_757.0, -32 - 27);
        double roll = Math.scalb(-192_253.0 * 2_000.0 * 4_295.0, -43 - 18);
        return new double[]{rate.getYawQ16() * planar, rate.getPitchQ16() * planar,
                rate.getRollQ16() * roll};
    }

    private static void assertWithinActiveEnvelope(double x, double y, double roll,
            IbisPrepositionPlanner.Limits limits) {
        double slack = 1e-9;
        assertTrue(Math.abs(x) <= 0.98 * limits.getMaximumX() + slack);
        assertTrue(Math.abs(y) <= 0.98 * limits.getMaximumY() + slack);
        assertTrue(Math.abs(roll) <= 0.98 * limits.getMaximumRoll() + slack);
        if (limits.isCoupled()) {
            assertTrue(Math.hypot(x, y) <= 0.98 * 2_546 + slack);
            assertTrue(Math.abs(x) + 1_628.0 / 32_768 * Math.abs(roll) <= 0.98 * 2_700 + slack);
            assertTrue(Math.abs(y) + 1_571.0 / 32_768 * Math.abs(roll) <= 0.98 * 2_700 + slack);
            // The native integer floor-product box is never tighter than these continuous bounds.
            assertTrue(Math.abs(x) <= 2_700 - Math.floor(Math.abs(roll) * 1_628 / 32_768));
            assertTrue(Math.abs(y) <= 2_700 - Math.floor(Math.abs(roll) * 1_571 / 32_768));
        }
    }

    private static IbisPrepositionPlanner.Plan plan(
            float focalMm, double yaw, double pitch, double roll, double seconds) {
        return IbisPrepositionPlanner.plan(focalMm, rate(yaw, pitch, roll), seconds);
    }

    private static SiderealRateCorrection rate(double yaw, double pitch, double roll) {
        return SiderealRateCorrection.fromBodyAngularVelocity(
                pitch * SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND,
                yaw * SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND,
                -roll * SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND);
    }

    private static void assertUnavailable(IbisPrepositionPlanner.Plan plan) {
        assertFalse(plan.isAvailable());
        assertEquals(0, plan.getLogicalX());
        assertEquals(0, plan.getLogicalY());
        assertEquals(0, plan.getLogicalRoll());
        assertTrue(Double.isNaN(plan.getMaximumSeconds()));
        assertTrue(Double.isNaN(plan.getPlannedSeconds()));
    }
}
