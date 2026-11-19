package dev.omshutter.solver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CameraAttitudeEstimatorTest {
    private static final long UTC = 1_787_315_696L;
    private static final long RECEIPT = 10_000_000_000L;
    private static final double LATITUDE = 37.7749;
    private static final double LONGITUDE = -122.4194;
    private static final double[] IDENTITY = {1.0, 0.0, 0.0, 0.0};
    private static final double ANGLE_TOLERANCE = 2.0e-6;

    @Test
    public void testZeroAnchorsArbitraryCurrentQuaternionWithoutCaptureOrSolve() {
        CameraAttitudeEstimator estimator = new CameraAttitudeEstimator();
        estimator.accept(100L, q30(product(axis(0, 37.0), axis(2, -51.0))), RECEIPT);
        assertEquals(CameraAttitudeEstimator.State.UNANCHORED,
                estimator.estimate(RECEIPT).getState());

        assertTrue(estimator.zeroForTesting(RECEIPT));
        CameraAttitudeEstimator.Estimate estimate = estimator.estimate(RECEIPT);
        assertAltAz(estimate, 0.0, 0.0);
        assertEquals(0.0, estimate.getRollDeg(), ANGLE_TOLERANCE);
        assertEquals(0.0, estimate.getAzimuthDeg(), ANGLE_TOLERANCE);
        assertEquals(-1L, estimate.getCaptureSequence());
        assertEquals(100L, estimate.getTick());
        assertEquals(RECEIPT, estimate.getReceivedElapsedRealtimeNanos());
    }

    @Test
    public void testZeroTracksKnownBodyYawPitchAndOrderedMixedRotation() {
        double[] initial = product(axis(2, 61.0), axis(0, -24.0));
        double[][] turns = {axis(1, 30.0), axis(0, 20.0),
                product(axis(1, 30.0), axis(0, 20.0)),
                product(axis(0, 20.0), axis(1, 30.0))};
        double reversedAltitude = Math.toDegrees(Math.asin(
                Math.sin(Math.toRadians(20.0)) * Math.cos(Math.toRadians(30.0))));
        double reversedAzimuth = Math.toDegrees(Math.atan2(Math.sin(Math.toRadians(30.0)),
                Math.cos(Math.toRadians(20.0)) * Math.cos(Math.toRadians(30.0))));
        double[][] expected = {{0.0, 30.0}, {20.0, 0.0}, {20.0, 30.0},
                {reversedAltitude, reversedAzimuth}};
        for (int i = 0; i < turns.length; i++) {
            CameraAttitudeEstimator estimator = new CameraAttitudeEstimator();
            estimator.accept(100L, q30(initial), RECEIPT);
            assertTrue(estimator.zeroForTesting(RECEIPT));
            long later = RECEIPT + 100_000_000L;
            estimator.accept(300L, q30(product(initial, turns[i])), later);
            assertAltAz(estimator.estimate(later), expected[i][0], expected[i][1]);
        }
    }

    @Test
    public void rollTracksBothDirectionsAndWrapsToSignedDegrees() {
        double[] initial = product(axis(0, 37.0), axis(2, -51.0));
        CameraAttitudeEstimator estimator = new CameraAttitudeEstimator();
        estimator.accept(100L, q30(initial), RECEIPT);
        assertTrue(estimator.zeroForTesting(RECEIPT));
        double[] rotations = {30.0, -30.0, 179.0, -179.0, 180.0, 200.0, 360.0};
        long tick = 300L;
        for (double degrees : rotations) {
            estimator.accept(tick++, q30(product(initial, axis(2, degrees))), RECEIPT + tick);
            CameraAttitudeEstimator.Estimate estimate = estimator.estimate(RECEIPT + tick);
            assertAltAz(estimate, 0.0, 0.0);
            double expected = Math.IEEEremainder(degrees, 360.0);
            if (expected >= 180.0) expected -= 360.0;
            assertEquals(0.0, Math.IEEEremainder(estimate.getRollDeg() - expected, 360.0),
                    ANGLE_TOLERANCE);
            assertTrue(estimate.getRollDeg() >= -180.0 && estimate.getRollDeg() < 180.0);
        }
    }

    @Test
    public void rollUsesLocalHorizonAfterMixedYawPitchAndRoll() {
        double[] initial = product(axis(1, 42.0), axis(0, -31.0));
        CameraAttitudeEstimator estimator = new CameraAttitudeEstimator();
        estimator.accept(100L, q30(initial), RECEIPT);
        assertTrue(estimator.zeroForTesting(RECEIPT));
        double[] motion = product(product(axis(1, 30.0), axis(0, 20.0)), axis(2, -40.0));
        estimator.accept(300L, q30(product(initial, motion)), RECEIPT + 100_000_000L);
        CameraAttitudeEstimator.Estimate estimate = estimator.estimate(RECEIPT + 100_000_000L);
        assertAltAz(estimate, 20.0, 30.0);
        assertEquals(-40.0, estimate.getRollDeg(), ANGLE_TOLERANCE);

        assertTrue(estimator.zeroForTesting(RECEIPT + 100_000_000L));
        estimate = estimator.estimate(RECEIPT + 100_000_000L);
        assertAltAz(estimate, 0.0, 0.0);
        assertEquals(0.0, estimate.getRollDeg(), ANGLE_TOLERANCE);
    }

    @Test
    public void rollIsUnavailableAtZenithAndNadirButDefinedNearby() {
        double[] initial = product(axis(1, 47.0), axis(2, -31.0));
        CameraAttitudeEstimator estimator = new CameraAttitudeEstimator();
        estimator.accept(100L, q30(initial), RECEIPT);
        assertTrue(estimator.zeroForTesting(RECEIPT));
        long tick = 300L;
        for (double pitch : new double[]{89.9, 90.0, -90.0, -89.9}) {
            double[] motion = product(axis(0, pitch), axis(2, 37.0));
            estimator.accept(tick++, q30(product(initial, motion)), RECEIPT + tick);
            CameraAttitudeEstimator.Estimate estimate = estimator.estimate(RECEIPT + tick);
            if (Math.abs(pitch) == 90.0) {
                assertEquals(pitch, estimate.getAltitudeDeg(), ANGLE_TOLERANCE);
                assertTrue(Double.isNaN(estimate.getAzimuthDeg()));
                assertTrue(Double.isNaN(estimate.getRollDeg()));
            } else {
                assertTrue(estimate.isAvailable());
                assertEquals(pitch, estimate.getAltitudeDeg(), ANGLE_TOLERANCE);
                // Q30 direction error is amplified by the nearly vertical viewing angle.
                assertEquals(0.0, Math.IEEEremainder(estimate.getAzimuthDeg(), 360.0), 0.0001);
                assertEquals(37.0, estimate.getRollDeg(), 0.0001);
            }
        }
        estimator.pause();
        assertTrue(Double.isNaN(estimator.estimate(RECEIPT + tick).getRollDeg()));
    }

    @Test
    public void testZeroCanReplaceAReferenceAtTheCurrentMovedPose() {
        CameraAttitudeEstimator estimator = anchored(IDENTITY, solve(23.0, -31.0, 18.0));
        double[] moved = product(axis(1, 30.0), axis(0, 20.0));
        long later = RECEIPT + 100_000_000L;
        estimator.accept(300L, q30(moved), later);
        assertTrue(estimator.zeroForTesting(later));
        assertAltAz(estimator.estimate(later), 0.0, 0.0);
        assertEquals(-1L, estimator.estimate(later).getCaptureSequence());

        long next = later + 100_000_000L;
        estimator.accept(500L, q30(product(moved, axis(0, 15.0))), next);
        assertAltAz(estimator.estimate(next), 15.0, 0.0);
        assertTrue(estimator.zeroForTesting(next));
        assertAltAz(estimator.estimate(next), 0.0, 0.0);
    }

    @Test
    public void testZeroRefusesMissingPausedAndDisconnectedSource() {
        CameraAttitudeEstimator estimator = new CameraAttitudeEstimator();
        assertFalse(estimator.zeroForTesting(RECEIPT));
        estimator.accept(100L, IDENTITY, RECEIPT);
        assertTrue(estimator.zeroForTesting(RECEIPT));
        estimator.pause();
        assertFalse(estimator.zeroForTesting(RECEIPT));
        long resumed = RECEIPT + 100_000_000L;
        estimator.accept(300L, q30(axis(0, 15.0)), resumed);
        assertAltAz(estimator.estimate(resumed), 15.0, 0.0);

        estimator.clear();
        assertFalse(estimator.zeroForTesting(resumed));
        estimator.accept(500L, IDENTITY, resumed + 100_000_000L);
        assertEquals(CameraAttitudeEstimator.State.UNANCHORED,
                estimator.estimate(resumed + 100_000_000L).getState());
    }

    @Test
    public void testZeroRefusesStaleOrFutureReceiptWithoutReplacingExistingAnchor() {
        CameraAttitudeEstimator estimator = anchored(IDENTITY, solve(23.0, -31.0, 18.0));
        assertFalse(estimator.zeroForTesting(RECEIPT - 1L));
        long stale = RECEIPT + CameraAttitudeEstimator.MAX_SAMPLE_AGE_NANOS + 1L;
        assertFalse(estimator.zeroForTesting(stale));
        estimator.accept(1200L, IDENTITY, stale);
        assertPointing(estimator.estimate(stale), 23.0, -31.0);
        assertEquals(1L, estimator.estimate(stale).getCaptureSequence());

        assertTrue(estimator.zeroForTesting(stale + CameraAttitudeEstimator.MAX_SAMPLE_AGE_NANOS));
        assertAltAz(estimator.estimate(stale), 0.0, 0.0);
    }

    @Test
    public void realSolveReplacesTestZeroAndRetainedCaptureRemainsUsable() {
        CameraAttitudeEstimator estimator = new CameraAttitudeEstimator();
        double[] initial = product(axis(0, 37.0), axis(2, -51.0));
        estimator.accept(100L, q30(initial), RECEIPT);
        CameraAttitudeEstimator.Capture capture = estimator.capture(7L, 100L, q30(initial));
        assertTrue(estimator.zeroForTesting(RECEIPT));
        assertTrue(estimator.anchor(solve(123.456, -20.25, 48.0), capture,
                LATITUDE, LONGITUDE, UTC, 0));
        assertPointing(estimator.estimate(RECEIPT), 123.456, -20.25);
        assertEquals(7L, estimator.estimate(RECEIPT).getCaptureSequence());
    }

    @Test
    public void captureMatchesExistingIauCoordinatesWithArbitraryGyroReference() {
        CameraAttitudeEstimator estimator = new CameraAttitudeEstimator();
        int[] captureQ = q30(product(axis(0, 37.0), axis(2, -51.0)));
        estimator.accept(100L, captureQ, RECEIPT);
        CameraAttitudeEstimator.Capture capture = estimator.capture(12L, 100L, captureQ);
        assertNotNull(capture);
        assertTrue(estimator.anchor(solve(123.456, -20.25, 48.0), capture,
                LATITUDE, LONGITUDE, UTC, 0));

        CameraAttitudeEstimator.Estimate estimate = estimator.estimate(RECEIPT);
        // Independently checked IAU reference also used by HorizontalCoordinatesTest.
        assertEquals(-10.46723975975163, estimate.getAltitudeDeg(), 1.0e-10);
        assertEquals(107.68907485045, estimate.getAzimuthDeg(), 1.0e-9);
        assertEquals(12L, estimate.getCaptureSequence());
    }

    @Test
    public void stationaryCameraKeepsAltAzWhileClockAdvancesAnHour() {
        CameraAttitudeEstimator estimator = anchored(IDENTITY, solve(123.456, -20.25, 48.0));
        CameraAttitudeEstimator.Estimate before = estimator.estimate(RECEIPT);
        long later = RECEIPT + 3_600_000_000_000L;
        estimator.accept(7_200_100L, IDENTITY, later);
        CameraAttitudeEstimator.Estimate after = estimator.estimate(later);

        assertTrue(after.isAvailable());
        assertEquals(before.getAltitudeDeg(), after.getAltitudeDeg(), 0.0);
        assertEquals(before.getAzimuthDeg(), after.getAzimuthDeg(), 0.0);
        HorizontalCoordinates movingSkyTarget = HorizontalCoordinates.fromIcrs(
                123.456, -20.25, LATITUDE, LONGITUDE, UTC + 3_600L, 0);
        assertTrue(Math.abs(movingSkyTarget.getAltitudeDeg() - after.getAltitudeDeg()) > 1.0);
    }

    @Test
    public void lateSolveUsesCapturePoseAndIncludesSubsequentNoncommutingMotion() {
        CameraAttitudeEstimator estimator = new CameraAttitudeEstimator();
        double[] captureQ = product(axis(2, 33.0), axis(1, -28.0));
        estimator.accept(100L, q30(captureQ), RECEIPT);
        CameraAttitudeEstimator.Capture capture = estimator.capture(9L, 100L, q30(captureQ));
        double[] currentQ = product(captureQ, product(axis(0, 90.0), axis(1, 90.0)));
        long afterSolve = RECEIPT + 30_000_000_000L;
        estimator.accept(60_100L, q30(currentQ), afterSolve);
        assertFalse(estimator.estimate(afterSolve).isAvailable());

        assertTrue(estimator.anchor(solve(0.0, 0.0, 0.0), capture,
                LATITUDE, LONGITUDE, UTC, 0));
        // X90 then Y90 points along capture body +X: celestial west at RA=270, Dec=0.
        assertPointing(estimator.estimate(afterSolve), 270.0, 0.0);
    }

    @Test
    public void reversingTheSameBodyAxisTurnsProducesDifferentPointing() {
        CameraAttitudeEstimator xy = anchored(IDENTITY, solve(0.0, 0.0, 0.0));
        CameraAttitudeEstimator yx = anchored(IDENTITY, solve(0.0, 0.0, 0.0));
        xy.accept(300L, q30(product(axis(0, 90.0), axis(1, 90.0))), RECEIPT + 100_000_000L);
        yx.accept(300L, q30(product(axis(1, 90.0), axis(0, 90.0))), RECEIPT + 100_000_000L);

        assertPointing(xy.estimate(RECEIPT + 100_000_000L), 270.0, 0.0);
        // Y90 then X90 points along capture body -Y: celestial north.
        assertPointing(yx.estimate(RECEIPT + 100_000_000L), 0.0, 90.0);
    }

    @Test
    public void solvedImageUpRollChangesHowBodyPitchMovesTheBoresight() {
        CameraAttitudeEstimator rollZero = anchored(IDENTITY, solve(0.0, 0.0, 0.0));
        CameraAttitudeEstimator rollNinety = anchored(IDENTITY, solve(0.0, 0.0, 90.0));
        rollZero.accept(300L, q30(axis(0, 90.0)), RECEIPT + 100_000_000L);
        rollNinety.accept(300L, q30(axis(0, 90.0)), RECEIPT + 100_000_000L);

        assertPointing(rollZero.estimate(RECEIPT + 100_000_000L), 0.0, 90.0);
        assertPointing(rollNinety.estimate(RECEIPT + 100_000_000L), 90.0, 0.0);
    }

    @Test
    public void duplicateTicksCannotKeepAStoppedSourceFreshButNotificationGapsRecover() {
        CameraAttitudeEstimator estimator = anchored(IDENTITY, solve(0.0, 0.0, 0.0));
        long stale = RECEIPT + CameraAttitudeEstimator.MAX_SAMPLE_AGE_NANOS + 1L;
        assertFalse(estimator.accept(100L, IDENTITY, stale));
        assertEquals(CameraAttitudeEstimator.State.STALE, estimator.estimate(stale).getState());

        long resumed = RECEIPT + 20_000_000_000L;
        assertTrue(estimator.accept(40_100L, q30(axis(0, 90.0)), resumed));
        assertPointing(estimator.estimate(resumed), 0.0, 90.0);
    }

    @Test
    public void captureCanBeRetainedAfterLongNotificationGapWithoutHistory() {
        CameraAttitudeEstimator estimator = new CameraAttitudeEstimator();
        estimator.accept(100L, IDENTITY, RECEIPT);
        long currentReceipt = RECEIPT + 60_000_000_000L;
        estimator.accept(120_100L, q30(axis(0, 90.0)), currentReceipt);
        // The capture latch itself can arrive with the delayed image/result.
        CameraAttitudeEstimator.Capture capture = estimator.capture(7L, 100L, IDENTITY);
        assertNotNull(capture);
        assertTrue(estimator.anchor(solve(0.0, 0.0, 0.0), capture,
                LATITUDE, LONGITUDE, UTC, 0));
        assertPointing(estimator.estimate(currentReceipt), 0.0, 90.0);
    }

    @Test
    public void pauseRetainsAnchorButReconnectRejectsOldCaptureTokens() {
        CameraAttitudeEstimator estimator = anchored(IDENTITY, solve(0.0, 0.0, 0.0));
        CameraAttitudeEstimator.Capture old = estimator.capture(3L, 100L, IDENTITY);
        estimator.pause();
        assertEquals(CameraAttitudeEstimator.State.UNAVAILABLE, estimator.estimate(RECEIPT).getState());
        assertFalse(estimator.accept(100L, IDENTITY, RECEIPT + 100_000_000L));
        assertFalse(estimator.estimate(RECEIPT + 100_000_000L).isAvailable());
        estimator.accept(200L, IDENTITY, RECEIPT + 200_000_000L);
        assertPointing(estimator.estimate(RECEIPT + 200_000_000L), 0.0, 0.0);
        assertTrue(estimator.anchor(solve(0.0, 0.0, 0.0), old, LATITUDE, LONGITUDE, UTC, 0));

        CameraAttitudeEstimator.Capture beforeDisconnect = estimator.capture(4L, 200L, IDENTITY);
        estimator.clear();
        assertEquals(CameraAttitudeEstimator.State.UNAVAILABLE,
                estimator.estimate(RECEIPT + 200_000_000L).getState());
        estimator.accept(400L, IDENTITY, RECEIPT + 300_000_000L);
        assertFalse(estimator.anchor(solve(0.0, 0.0, 0.0), beforeDisconnect,
                LATITUDE, LONGITUDE, UTC, 0));
        assertNull(estimator.capture(4L, 200L, IDENTITY));
    }

    @Test
    public void tickWrapPreservesContinuityButRegressionDoesNot() {
        CameraAttitudeEstimator estimator = new CameraAttitudeEstimator();
        estimator.accept(0xffff_fff0L, IDENTITY, RECEIPT);
        CameraAttitudeEstimator.Capture capture = estimator.capture(0xffff_ffffL,
                0xffff_fff0L, IDENTITY);
        estimator.anchor(solve(0.0, 0.0, 0.0), capture, LATITUDE, LONGITUDE, UTC, 0);
        estimator.accept(20L, IDENTITY, RECEIPT + 100_000_000L);
        assertTrue(estimator.estimate(RECEIPT + 100_000_000L).isAvailable());
        estimator.accept(19L, IDENTITY, RECEIPT + 200_000_000L);
        assertFalse(estimator.estimate(RECEIPT + 200_000_000L).isAvailable());
        assertFalse(estimator.anchor(solve(0.0, 0.0, 0.0), capture,
                LATITUDE, LONGITUDE, UTC, 0));
        assertNull(estimator.capture(0L, 20L, IDENTITY));
    }

    @Test
    public void replacingAnchorDoesNotInvalidateCurrentPoseOrRetainedCapture() {
        CameraAttitudeEstimator estimator = anchored(IDENTITY, solve(0.0, 0.0, 0.0));
        CameraAttitudeEstimator.Capture capture = estimator.capture(8L, 100L, IDENTITY);
        estimator.clearAnchor();
        assertEquals(CameraAttitudeEstimator.State.UNANCHORED, estimator.estimate(RECEIPT).getState());
        assertTrue(estimator.anchor(solve(23.0, -31.0, 18.0), capture,
                LATITUDE, LONGITUDE, UTC, 0));
        assertPointing(estimator.estimate(RECEIPT), 23.0, -31.0);
    }

    @Test
    public void malformedCurrentPoseFailsClosedAndInputsAreDefensivelyCopied() {
        double[] mutable = IDENTITY.clone();
        CameraAttitudeEstimator estimator = anchored(mutable, solve(0.0, 0.0, 0.0));
        mutable[0] = 0.0;
        mutable[1] = 1.0;
        assertPointing(estimator.estimate(RECEIPT), 0.0, 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> estimator.accept(300L, new int[4], RECEIPT + 100_000_000L));
        assertEquals(CameraAttitudeEstimator.State.UNAVAILABLE,
                estimator.estimate(RECEIPT + 100_000_000L).getState());
        estimator.accept(400L, IDENTITY, RECEIPT + 200_000_000L);
        assertPointing(estimator.estimate(RECEIPT + 200_000_000L), 0.0, 0.0);
    }

    @Test
    public void exactZenithAndNadirKeepAltitudeWithoutInventingAzimuth() {
        for (int sign : new int[]{1, -1}) {
            double latitude = Math.toRadians(LATITUDE);
            double sidereal = GravityRollEstimator.greenwichMeanSiderealTimeRadians(UTC, 0)
                    + Math.toRadians(LONGITUDE);
            double[] targetOfDate = {sign * Math.cos(latitude) * Math.cos(sidereal),
                    sign * Math.cos(latitude) * Math.sin(sidereal), sign * Math.sin(latitude)};
            double[][] p = GravityRollEstimator.icrsToMeanEquatorOfDateMatrix(UTC, 0);
            double[] targetIcrs = new double[3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) targetIcrs[i] += p[j][i] * targetOfDate[j];
            }
            double ra = Math.toDegrees(Math.atan2(targetIcrs[1], targetIcrs[0]));
            double dec = Math.toDegrees(Math.asin(targetIcrs[2]));
            CameraAttitudeEstimator estimator = anchored(IDENTITY, solve(ra, dec, 22.0));
            CameraAttitudeEstimator.Estimate estimate = estimator.estimate(RECEIPT);
            assertTrue(estimate.isAvailable());
            assertEquals(sign * 90.0, estimate.getAltitudeDeg(), 1.0e-9);
            assertTrue(Double.isNaN(estimate.getAzimuthDeg()));
            assertTrue(Double.isNaN(estimate.getRollDeg()));
        }
    }

    private static CameraAttitudeEstimator anchored(double[] captureQ, SolveResult solve) {
        CameraAttitudeEstimator estimator = new CameraAttitudeEstimator();
        estimator.accept(100L, captureQ, RECEIPT);
        CameraAttitudeEstimator.Capture capture = estimator.capture(1L, 100L, captureQ);
        assertTrue(estimator.anchor(solve, capture, LATITUDE, LONGITUDE, UTC, 0));
        return estimator;
    }

    private static void assertPointing(CameraAttitudeEstimator.Estimate estimate, double ra, double dec) {
        HorizontalCoordinates expected = HorizontalCoordinates.fromIcrs(ra, dec,
                LATITUDE, LONGITUDE, UTC, 0);
        assertTrue(estimate.isAvailable());
        assertEquals(expected.getAltitudeDeg(), estimate.getAltitudeDeg(), ANGLE_TOLERANCE);
        assertEquals(0.0, Math.IEEEremainder(estimate.getAzimuthDeg() - expected.getAzimuthDeg(), 360.0),
                ANGLE_TOLERANCE);
    }

    private static void assertAltAz(CameraAttitudeEstimator.Estimate estimate, double alt, double az) {
        assertTrue(estimate.isAvailable());
        assertEquals(alt, estimate.getAltitudeDeg(), ANGLE_TOLERANCE);
        assertEquals(0.0, Math.IEEEremainder(estimate.getAzimuthDeg() - az, 360.0), ANGLE_TOLERANCE);
    }

    private static SolveResult solve(double ra, double dec, double roll) {
        return new SolveResult(ra, dec, roll, 4.0, 1.0, new int[]{0, 1, 2, 3}, new int[]{4});
    }

    private static int[] q30(double[] q) {
        int[] result = new int[4];
        for (int i = 0; i < 4; i++) result[i] = (int) Math.round(q[i] * 1_073_741_824.0);
        return result;
    }

    private static double[] axis(int axis, double degrees) {
        double halfAngle = Math.toRadians(degrees) / 2.0;
        double[] result = {Math.cos(halfAngle), 0.0, 0.0, 0.0};
        result[axis + 1] = Math.sin(halfAngle);
        return result;
    }

    private static double[] product(double[] a, double[] b) {
        return new double[]{a[0]*b[0] - a[1]*b[1] - a[2]*b[2] - a[3]*b[3],
                a[0]*b[1] + a[1]*b[0] + a[2]*b[3] - a[3]*b[2],
                a[0]*b[2] - a[1]*b[3] + a[2]*b[0] + a[3]*b[1],
                a[0]*b[3] + a[1]*b[2] - a[2]*b[1] + a[3]*b[0]};
    }
}
