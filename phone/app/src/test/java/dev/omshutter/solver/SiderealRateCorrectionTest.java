package dev.omshutter.solver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import org.junit.Test;

public final class SiderealRateCorrectionTest {
    private static final Instant J2000 = Instant.parse("2000-01-01T12:00:00Z");
    private static final Instant OBSERVATION = Instant.parse("2026-09-12T00:00:00Z");

    @Test
    public void cardinalAttitudesLockAxisOrderAndSigns() {
        assertCounts(
                SiderealRateCorrection.fromEquatorialAttitude(0.0, 0.0, 0.0, 1.0, J2000),
                -729, 0, 0);
        assertCounts(
                SiderealRateCorrection.fromEquatorialAttitude(0.0, 0.0, 90.0, 1.0, J2000),
                0, 729, 0);
        assertCounts(
                SiderealRateCorrection.fromEquatorialAttitude(0.0, 90.0, -37.0, 1.0, J2000),
                0, 0, -729);
        assertCounts(
                SiderealRateCorrection.fromEquatorialAttitude(0.0, -90.0, 123.0, 1.0, J2000),
                0, 0, 729);
    }

    @Test
    public void rightAscensionChangesPoleOfDateProjectionButFullTurnsDoNot() {
        SiderealRateCorrection expected =
                SiderealRateCorrection.fromEquatorialAttitude(0.0, 35.0, -62.0, 1.0, OBSERVATION);

        assertNotEquals(
                expected,
                SiderealRateCorrection.fromEquatorialAttitude(147.25, 35.0, -62.0, 1.0, OBSERVATION));
        assertEquals(
                expected,
                SiderealRateCorrection.fromEquatorialAttitude(-720.0, 35.0, -62.0, 1.0, OBSERVATION));
        assertEquals(
                expected,
                SiderealRateCorrection.fromEquatorialAttitude(1080.0, 35.0, -62.0, 1.0, OBSERVATION));
    }

    @Test
    public void plateSolveAdapterUsesDeclinationAndImageUpPositionAngle() {
        SolveResult result = new SolveResult(
                219.0,
                30.0,
                45.0,
                4.0,
                1.0,
                new int[]{0, 1, 2, 3},
                new int[]{4});

        assertCounts(SiderealRateCorrection.fromPlateSolve(result, 1.0, J2000), -447, 447, -365);
        assertCounts(SiderealRateCorrection.fromPlateSolve(result, 0.5, J2000), -223, 223, -182);
        SiderealRateCorrection dated = SiderealRateCorrection.fromPlateSolve(result, 0.5, OBSERVATION);
        assertEquals(SiderealRateCorrection.fromEquatorialAttitude(
                219.0, 30.0, 45.0, 0.5, OBSERVATION), dated);
        assertNotEquals(SiderealRateCorrection.fromPlateSolve(result, 0.5, J2000), dated);
    }

    @Test
    public void strengthScalesBeforeFinalSymmetricQuantization() {
        assertCounts(
                SiderealRateCorrection.fromEquatorialAttitude(0.0, 0.0, 0.0, 0.5, J2000),
                -365, 0, 0);
        assertCounts(
                SiderealRateCorrection.fromEquatorialAttitude(0.0, 0.0, 0.0, 0.0, OBSERVATION),
                0, 0, 0);
        assertThrows(IllegalArgumentException.class,
                () -> SiderealRateCorrection.fromEquatorialAttitude(0, 0, 0, -0.01));
        assertThrows(IllegalArgumentException.class,
                () -> SiderealRateCorrection.fromEquatorialAttitude(0, 0, 0, 1.01));
        assertThrows(IllegalArgumentException.class,
                () -> SiderealRateCorrection.fromEquatorialAttitude(0, 0, 0, Double.NaN));
    }

    @Test
    public void bodyMappingMatchesDocumentedWireOrderAndRollPolarity() {
        double oneCount = SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND;

        assertCounts(
                SiderealRateCorrection.fromBodyAngularVelocity(oneCount, 0.0, 0.0),
                0, 1, 0);
        assertCounts(
                SiderealRateCorrection.fromBodyAngularVelocity(0.0, oneCount, 0.0),
                1, 0, 0);
        assertCounts(
                SiderealRateCorrection.fromBodyAngularVelocity(0.0, 0.0, oneCount),
                0, 0, -1);
    }

    @Test
    public void bodyRatesQuantizeSymmetricallyAndClampEachLaneIndependently() {
        double unit = SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND;
        assertCounts(
                SiderealRateCorrection.fromBodyAngularVelocity(
                        0.5 * unit, -0.5 * unit, -0.5 * unit),
                -1, 1, 1);
        assertCounts(
                SiderealRateCorrection.fromBodyAngularVelocity(1.0, -1.0, 1.0),
                -SiderealRateCorrection.MAX_WIRE_COUNT,
                SiderealRateCorrection.MAX_WIRE_COUNT,
                -SiderealRateCorrection.MAX_WIRE_COUNT);
        assertCounts(
                SiderealRateCorrection.fromBodyAngularVelocity(
                        Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE),
                -SiderealRateCorrection.MAX_WIRE_COUNT,
                SiderealRateCorrection.MAX_WIRE_COUNT,
                -SiderealRateCorrection.MAX_WIRE_COUNT);
    }

    @Test
    public void equatorialRateRetainsQ16FractionWhileLegacyViewIsUnchanged() {
        SiderealRateCorrection correction =
                SiderealRateCorrection.fromEquatorialAttitude(0.0, 0.0, 0.0, 1.0, J2000);

        assertEquals(-47_789_605, correction.getYawQ16());
        // P03 includes the small ICRS-to-J2000 frame bias. It is visible in
        // Q16.16 even though the legacy cardinal rates still round to zero.
        assertEquals(2, correction.getPitchQ16());
        assertEquals(4, correction.getRollQ16());
        assertEquals(-729, correction.getYawCounts());
        assertEquals(
                correction.getYawQ16()
                        * SiderealRateCorrection.WIRE_Q16_UNIT_RAD_PER_SECOND,
                correction.getYawRadiansPerSecond(),
                0.0);
    }

    @Test
    public void q16BodyMappingPreservesAxisOrderSignsAndFractionalCounts() {
        double unit = SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND;
        SiderealRateCorrection correction =
                SiderealRateCorrection.fromBodyAngularVelocity(
                        1.25 * unit,
                        -2.5 * unit,
                        0.125 * unit);

        assertEquals(-163_840, correction.getYawQ16());
        assertEquals(81_920, correction.getPitchQ16());
        assertEquals(-8_192, correction.getRollQ16());
        assertEquals(-2.5 * unit, correction.getYawRadiansPerSecond(), 1.0e-20);
        assertEquals(1.25 * unit, correction.getPitchRadiansPerSecond(), 1.0e-20);
        assertEquals(-0.125 * unit, correction.getRollRadiansPerSecond(), 1.0e-20);
    }

    @Test
    public void q16QuantizationAndLegacyViewBothRoundHalfAwayFromZero() {
        double q16Unit = SiderealRateCorrection.WIRE_Q16_UNIT_RAD_PER_SECOND;
        double legacyUnit = SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND;

        SiderealRateCorrection subQ16 = SiderealRateCorrection.fromBodyAngularVelocity(
                0.49 * q16Unit, -0.49 * q16Unit, 0.0);
        assertEquals(0, subQ16.getPitchQ16());
        assertEquals(0, subQ16.getYawQ16());

        SiderealRateCorrection halfQ16 = SiderealRateCorrection.fromBodyAngularVelocity(
                0.5 * q16Unit, -0.5 * q16Unit, 0.0);
        assertEquals(1, halfQ16.getPitchQ16());
        assertEquals(-1, halfQ16.getYawQ16());

        SiderealRateCorrection halfLegacy = SiderealRateCorrection.fromBodyAngularVelocity(
                0.5 * legacyUnit, -0.5 * legacyUnit, 0.5 * legacyUnit);
        assertCounts(halfLegacy, -1, 1, -1);
    }

    @Test
    public void legacyViewRemainsDirectlyQuantizedAcrossQ16HalfBoundarySliver() {
        double q16QuarterCount = 0.25 / SiderealRateCorrection.WIRE_Q16_SCALE;
        double justBelowHalf = 0.5 - q16QuarterCount;
        double atHalf = 0.5;
        double unit = SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND;

        SiderealRateCorrection below = SiderealRateCorrection.fromBodyAngularVelocity(
                justBelowHalf * unit,
                -justBelowHalf * unit,
                0.0);
        SiderealRateCorrection half = SiderealRateCorrection.fromBodyAngularVelocity(
                atHalf * unit,
                -atHalf * unit,
                0.0);

        // Both exact inputs round to the same Q16.16 value. V1 nevertheless retains the old
        // direct-double nearest-count result on the two sides of the legacy half-count boundary.
        assertEquals(32_768, below.getPitchQ16());
        assertEquals(half.getPitchQ16(), below.getPitchQ16());
        assertEquals(-32_768, below.getYawQ16());
        assertEquals(half.getYawQ16(), below.getYawQ16());
        assertCounts(below, 0, 0, 0);
        assertCounts(half, -1, 1, 0);
        assertNotEquals(
                "transport-visible legacy lanes are part of value identity",
                below,
                half);
    }

    @Test
    public void q16LanesClampAtTheExistingPhysicalLimit() {
        SiderealRateCorrection correction =
                SiderealRateCorrection.fromBodyAngularVelocity(1.0, -1.0, 1.0);

        assertEquals(-SiderealRateCorrection.MAX_WIRE_Q16, correction.getYawQ16());
        assertEquals(SiderealRateCorrection.MAX_WIRE_Q16, correction.getPitchQ16());
        assertEquals(-SiderealRateCorrection.MAX_WIRE_Q16, correction.getRollQ16());
        assertCounts(
                correction,
                -SiderealRateCorrection.MAX_WIRE_COUNT,
                SiderealRateCorrection.MAX_WIRE_COUNT,
                -SiderealRateCorrection.MAX_WIRE_COUNT);
    }

    @Test
    public void legacyWireFactoryPromotesWholeCountsExactly() {
        SiderealRateCorrection correction =
                SiderealRateCorrection.fromLegacyWireCounts(-729, 118, -638);

        assertCounts(correction, -729, 118, -638);
        assertEquals(-729 * SiderealRateCorrection.WIRE_Q16_SCALE, correction.getYawQ16());
        assertEquals(118 * SiderealRateCorrection.WIRE_Q16_SCALE, correction.getPitchQ16());
        assertEquals(-638 * SiderealRateCorrection.WIRE_Q16_SCALE, correction.getRollQ16());
        assertThrows(
                IllegalArgumentException.class,
                () -> SiderealRateCorrection.fromLegacyWireCounts(
                        SiderealRateCorrection.MAX_WIRE_COUNT + 1, 0, 0));
    }

    @Test
    public void q16WireFactoryCanonicalizesLegacyViewFromTransmittedLanes() {
        SiderealRateCorrection below = SiderealRateCorrection.fromQ16WireCounts(
                -32_767, 32_767, 0);
        SiderealRateCorrection half = SiderealRateCorrection.fromQ16WireCounts(
                -32_768, 32_768, 0);

        assertEquals(0, below.getYawCounts());
        assertEquals(0, below.getPitchCounts());
        assertCounts(half, -1, 1, 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> SiderealRateCorrection.fromQ16WireCounts(
                        SiderealRateCorrection.MAX_WIRE_Q16 + 1, 0, 0));
    }

    @Test
    public void rejectsInvalidAttitudesAndBodyRates() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SiderealRateCorrection.fromPlateSolve(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> SiderealRateCorrection.fromEquatorialAttitude(
                        Double.NaN, 0.0, 0.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> SiderealRateCorrection.fromEquatorialAttitude(
                        0.0, 90.0001, 0.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> SiderealRateCorrection.fromEquatorialAttitude(
                        0.0, 0.0, Double.POSITIVE_INFINITY));
        assertThrows(
                IllegalArgumentException.class,
                () -> SiderealRateCorrection.fromBodyAngularVelocity(
                        0.0, Double.NEGATIVE_INFINITY, 0.0));
    }

    @Test
    public void cameraPointedAlongMeanPoleOfDateNeedsOnlyRoll() {
        double[] pole = earthPole(OBSERVATION);
        double rightAscension = Math.toDegrees(Math.atan2(pole[1], pole[0]));
        double declination = Math.toDegrees(Math.atan2(pole[2], Math.hypot(pole[0], pole[1])));
        SiderealRateCorrection expected = SiderealRateCorrection.fromBodyAngularVelocity(
                0.0, 0.0, SiderealRateCorrection.SIDEREAL_RATE_RAD_PER_SECOND);

        for (double positionAngle : new double[]{-123.0, 0.0, 81.0}) {
            assertEquals(expected, SiderealRateCorrection.fromEquatorialAttitude(
                    rightAscension, declination, positionAngle, 1.0, OBSERVATION));
        }
    }

    @Test
    public void oppositeEquatorialRightAscensionsReverseTransversePoleComponents() {
        SiderealRateCorrection zeroRa = SiderealRateCorrection.fromEquatorialAttitude(
                0.0, 0.0, 0.0, 1.0, OBSERVATION);
        SiderealRateCorrection halfTurn = SiderealRateCorrection.fromEquatorialAttitude(
                180.0, 0.0, 0.0, 1.0, OBSERVATION);

        assertEquals(zeroRa.getYawQ16(), halfTurn.getYawQ16());
        assertEquals(-zeroRa.getPitchQ16(), halfTurn.getPitchQ16());
        assertEquals(-zeroRa.getRollQ16(), halfTurn.getRollQ16());
        // Independently evaluated P03 pole: (0.002593638311957595,
        // -0.000007808087532202, 0.9999966364840136).
        assertEquals(-47_789_444, zeroRa.getYawQ16());
        assertEquals(373, zeroRa.getPitchQ16());
        assertEquals(-123_949, zeroRa.getRollQ16());
    }

    @Test
    public void icrsNorthApproximationHasHalfPixelRateVectorErrorAt300mmFor30Seconds() {
        double ra = 123.0;
        double dec = Math.toRadians(35.0);
        double pa = Math.toRadians(-62.0);
        double rate = SiderealRateCorrection.SIDEREAL_RATE_RAD_PER_SECOND;
        SiderealRateCorrection dated = SiderealRateCorrection.fromEquatorialAttitude(
                ra, Math.toDegrees(dec), Math.toDegrees(pa), 1.0, OBSERVATION);
        SiderealRateCorrection icrsNorth = SiderealRateCorrection.fromBodyAngularVelocity(
                rate * Math.cos(dec) * Math.sin(pa),
                -rate * Math.cos(dec) * Math.cos(pa),
                rate * Math.sin(dec));
        double error = Math.hypot(Math.hypot(
                dated.getYawRadiansPerSecond() - icrsNorth.getYawRadiansPerSecond(),
                dated.getPitchRadiansPerSecond() - icrsNorth.getPitchRadiansPerSecond()),
                dated.getRollRadiansPerSecond() - icrsNorth.getRollRadiansPerSecond());
        double pixelPitchMm = 0.00335;
        double pixelErrorBound = error * 300.0 * 30.0 / pixelPitchMm;

        // This is the angular-rate-vector norm converted to a focal-plane
        // displacement bound, not a claim about one target's center drift.
        assertEquals(0.508116102, pixelErrorBound, 0.00001);
        double[] pole = earthPole(OBSERVATION);
        double expectedError = rate * Math.hypot(Math.hypot(pole[0], pole[1]), pole[2] - 1.0);
        assertEquals(expectedError, error, 2.0 * SiderealRateCorrection.WIRE_Q16_UNIT_RAD_PER_SECOND);
    }

    private static double[] earthPole(Instant instant) {
        return GravityRollEstimator.icrsToMeanEquatorOfDateMatrix(
                instant.getEpochSecond(), instant.getNano())[2];
    }

    private static void assertCounts(
            SiderealRateCorrection correction,
            int expectedYaw,
            int expectedPitch,
            int expectedRoll) {
        assertEquals(expectedYaw, correction.getYawCounts());
        assertEquals(expectedPitch, correction.getPitchCounts());
        assertEquals(expectedRoll, correction.getRollCounts());
    }
}
