package dev.omshutter.solver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.omshutter.protocol.StarTelemetry;

import org.junit.Test;

public final class TrackingExposureEstimatorTest {
    private static final int QUALIFIED_FLAGS =
            StarTelemetry.IBIS_FLAG_SOURCE_MAPPED
                    | StarTelemetry.IBIS_FLAG_MIRROR_ENABLED
                    | StarTelemetry.IBIS_FLAG_DOUBLE_READ_STABLE;

    private static final TrackingExposureEstimator.RemainingProxy TEST_PROXY =
            new TrackingExposureEstimator.RemainingProxy(2_048, 4_096, 2_048.0, 4_096.0);
    private static final TrackingExposureEstimator.Calibration TEST_CALIBRATION =
            new TrackingExposureEstimator.Calibration(1.0, 1.0, TEST_PROXY);

    @Test
    public void currentAcknowledgedGenerationProducesCenteredEstimate() {
        SiderealRateCorrection rate = rate(1_000, 0, 0);
        TrackingExposureEstimator.Estimate estimate = estimate(
                TEST_CALIBRATION, true, true, true, 7L, 7L, 100.0f, rate, null);

        // 100 mm * 1000 * 1e-7 = 0.01 mm/s, against a 1 mm test radius.
        assertTrue(estimate.hasCenteredSeconds());
        assertEquals(100.0, estimate.getCenteredSeconds(), 1.0e-9);
        assertFalse(estimate.hasRemainingSeconds());
    }

    @Test
    public void subLegacyCountRateStillProducesAHighResolutionEstimate() {
        double quarterCount = 0.25 * SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND;
        SiderealRateCorrection rate =
                SiderealRateCorrection.fromBodyAngularVelocity(0.0, quarterCount, 0.0);
        assertEquals(0, rate.getYawCounts());
        assertEquals(SiderealRateCorrection.WIRE_Q16_SCALE / 4, rate.getYawQ16());

        TrackingExposureEstimator.Estimate estimate = estimate(
                TEST_CALIBRATION, true, true, true, 7L, 7L, 100.0f, rate, null);

        // 100 mm * 0.25e-7 rad/s = 2.5e-6 mm/s, against a 1 mm test radius.
        assertTrue(estimate.hasCenteredSeconds());
        assertEquals(400_000.0, estimate.getCenteredSeconds(), 1.0e-6);
    }

    @Test
    public void staleOrUnhealthyRateNeverProducesAProductValue() {
        SiderealRateCorrection rate = rate(1_000, 0, 0);
        assertUnavailable(estimate(
                TEST_CALIBRATION, true, true, true, 8L, 7L, 100.0f, rate, null));
        assertUnavailable(estimate(
                TEST_CALIBRATION, false, true, true, 7L, 7L, 100.0f, rate, null));
        assertUnavailable(estimate(
                TEST_CALIBRATION, true, false, true, 7L, 7L, 100.0f, rate, null));
        assertUnavailable(estimate(
                TEST_CALIBRATION, true, true, false, 7L, 7L, 100.0f, rate, null));
        assertUnavailable(estimate(
                null, true, true, true, 7L, 7L, 100.0f, rate, null));
    }

    @Test
    public void remainingRequiresEveryQualifiedQ10FlagAndAnInstalledProxy() {
        SiderealRateCorrection rate = rate(1_000, 0, 0);
        for (int missing : new int[]{
                StarTelemetry.IBIS_FLAG_SOURCE_MAPPED,
                StarTelemetry.IBIS_FLAG_MIRROR_ENABLED,
                StarTelemetry.IBIS_FLAG_DOUBLE_READ_STABLE}) {
            int flags = QUALIFIED_FLAGS & ~missing;
            // Some flag subsets are forbidden by the protocol constructor.  Use the strongest
            // valid degraded state for each omitted prerequisite.
            if (missing == StarTelemetry.IBIS_FLAG_SOURCE_MAPPED) flags = 0;
            if (missing == StarTelemetry.IBIS_FLAG_MIRROR_ENABLED) {
                flags = StarTelemetry.IBIS_FLAG_SOURCE_MAPPED;
            }
            StarTelemetry.IbisSample sample =
                    new StarTelemetry.IbisSample(flags, 1, 0, 0, 0);
            TrackingExposureEstimator.Estimate estimate = estimate(
                    TEST_CALIBRATION, true, true, true, 7L, 7L, 100.0f, rate, sample);
            assertTrue(estimate.hasCenteredSeconds());
            assertFalse(estimate.hasRemainingSeconds());
        }

        TrackingExposureEstimator.Calibration withoutProxy =
                new TrackingExposureEstimator.Calibration(1.0, 1.0, null);
        TrackingExposureEstimator.Estimate estimate = estimate(
                withoutProxy,
                true,
                true,
                true,
                7L,
                7L,
                100.0f,
                rate,
                sample(0, 0, 0));
        assertTrue(estimate.hasCenteredSeconds());
        assertFalse(estimate.hasRemainingSeconds());
    }

    @Test
    public void centeredQ10LeavesAlmostAllQuantizationBoundedHeadroom() {
        SiderealRateCorrection rate = rate(1_000, 0, 0);
        TrackingExposureEstimator.Estimate estimate = estimate(
                TEST_CALIBRATION,
                true,
                true,
                true,
                7L,
                7L,
                100.0f,
                rate,
                sample(0, 0, 0));

        assertTrue(estimate.hasRemainingSeconds());
        assertEquals(100.0 * (1.0 - Math.hypot(1.0, 1.0) / 2_048.0),
                estimate.getRemainingSeconds(), 1.0e-9);
    }

    @Test
    public void nearestPlanarOrRollBoundaryLimitsRemainingTime() {
        SiderealRateCorrection planar = rate(1_000, 0, 0);
        TrackingExposureEstimator.Estimate planarEstimate = estimate(
                TEST_CALIBRATION,
                true,
                true,
                true,
                7L,
                7L,
                100.0f,
                planar,
                sample(512, 0, 0));
        assertTrue(planarEstimate.hasRemainingSeconds());
        assertTrue(planarEstimate.getRemainingSeconds() < planarEstimate.getCenteredSeconds());

        // A saturated display-space lane has no finite inverse, so the proxy is unavailable.
        SiderealRateCorrection roll = rate(0, 0, 1_000);
        TrackingExposureEstimator.Estimate rollEstimate = estimate(
                TEST_CALIBRATION,
                true,
                true,
                true,
                7L,
                7L,
                100.0f,
                roll,
                sample(0, 0, -1_024));
        assertFalse(rollEstimate.hasRemainingSeconds());
    }

    @Test
    public void inverseQ10BoundIsSymmetricAndNeverRoundsDown() {
        assertEquals(1, TrackingExposureEstimator.rawMagnitudeUpperBound(0, 1_800));
        assertEquals(1, TrackingExposureEstimator.rawMagnitudeUpperBound(-0, 1_800));
        assertEquals(
                TrackingExposureEstimator.rawMagnitudeUpperBound(511, 1_800),
                TrackingExposureEstimator.rawMagnitudeUpperBound(-511, 1_800));
        assertEquals(1_800, TrackingExposureEstimator.rawMagnitudeUpperBound(1_024, 1_800));
        assertEquals(1_800, TrackingExposureEstimator.rawMagnitudeUpperBound(-1_024, 1_800));
    }

    @Test
    public void recoveredProxyPublishesCenteredAndQualifiedRemainingEstimates() {
        SiderealRateCorrection rate = rate(1_000, 0, 0);
        TrackingExposureEstimator.Estimate estimate =
                TrackingExposureEstimator.estimateRecoveredQ10Proxy(
                        true, true, true, 7L, 7L, 100.0f, rate, sample(0, 0, 0));

        assertTrue(estimate.hasCenteredSeconds());
        assertTrue(estimate.hasRemainingSeconds());
        assertEquals((1_642.0 / 3_000.0) / 0.01,
                estimate.getCenteredSeconds(), 1.0e-9);
        assertTrue(estimate.getRemainingSeconds() <= estimate.getCenteredSeconds());
    }

    @Test
    public void saturatedQ10KeepsCenteredButWithholdsRemainingEstimate() {
        SiderealRateCorrection rate = rate(1_000, 0, 0);
        TrackingExposureEstimator.Estimate estimate =
                TrackingExposureEstimator.estimateRecoveredQ10Proxy(
                        true, true, true, 7L, 7L, 100.0f, rate,
                        sample(StarTelemetry.IBIS_Q10_FULL_SCALE, 0, 0));

        assertTrue(estimate.hasCenteredSeconds());
        assertFalse(estimate.hasRemainingSeconds());
    }

    private static TrackingExposureEstimator.Estimate estimate(
            TrackingExposureEstimator.Calibration calibration,
            boolean correctionEnabled,
            boolean active,
            boolean healthy,
            long generation,
            long acknowledgedGeneration,
            float focalMm,
            SiderealRateCorrection rate,
            StarTelemetry.IbisSample sample) {
        return TrackingExposureEstimator.estimate(
                calibration,
                correctionEnabled,
                active,
                healthy,
                generation,
                acknowledgedGeneration,
                focalMm,
                rate,
                sample);
    }

    private static StarTelemetry.IbisSample sample(int x, int y, int roll) {
        return new StarTelemetry.IbisSample(QUALIFIED_FLAGS, 1, x, y, roll);
    }

    private static SiderealRateCorrection rate(int yaw, int pitch, int roll) {
        return SiderealRateCorrection.fromBodyAngularVelocity(
                pitch * SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND,
                yaw * SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND,
                -roll * SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND);
    }

    private static void assertUnavailable(TrackingExposureEstimator.Estimate estimate) {
        assertFalse(estimate.hasCenteredSeconds());
        assertFalse(estimate.hasRemainingSeconds());
    }
}
