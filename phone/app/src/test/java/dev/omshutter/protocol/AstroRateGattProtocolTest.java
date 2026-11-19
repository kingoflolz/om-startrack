package dev.omshutter.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.omshutter.solver.SiderealRateCorrection;

import org.junit.Test;

public final class AstroRateGattProtocolTest {
    @Test
    public void runtimeAliasUsesV2NegativeYawGoldenVector() {
        SiderealRateCorrection correction =
                SiderealRateCorrection.fromBodyAngularVelocity(
                        0.0, -SiderealRateCorrection.SIDEREAL_RATE_RAD_PER_SECOND, 0.0);

        assertArrayEquals(
                new byte[]{
                        (byte) 0xdb, (byte) 0xc9, 0x26, (byte) 0xfd,
                        0, 0, 0, 0, 0, 0, 0, 0,
                        (byte) 0xff, 0x0f, 0, 0, 0, 0},
                AstroRateGattProtocol.setpoint(
                        correction, AstroRateGattProtocol.APP_TTL_TICKS));
        assertArrayEquals(
                AstroRateGattProtocol.setpoint(correction, AstroRateGattProtocol.APP_TTL_TICKS),
                AstroRateGattProtocol.setpointV2(
                        correction, AstroRateGattProtocol.APP_TTL_TICKS));
    }

    @Test
    public void integratedLeaseUsesTwoSecondCpuTtlAndOneSecondPhoneCadence() {
        assertEquals(4_095, AstroRateGattProtocol.APP_TTL_TICKS);
        assertEquals(1_000L, AstroRateGattProtocol.APP_RENEWAL_MS);
        assertEquals(50L, AstroRateGattProtocol.APP_FAST_RETRY_MS);
    }

    @Test
    public void ttlIsUnsignedLittleEndianAtBytesTwelveAndThirteen() {
        SiderealRateCorrection correction =
                SiderealRateCorrection.fromBodyAngularVelocity(1.0e-7, 0.0, 0.0);

        assertArrayEquals(
                new byte[]{
                        0, 0, 0, 0, 0, 0x00, 0x01, 0x00,
                        0, 0, 0, 0, 0x34, 0x12, 0, 0, 0, 0},
                AstroRateGattProtocol.setpoint(correction, 0x1234));
    }

    @Test
    public void cancelIsExactlyEighteenZeroBytes() {
        assertArrayEquals(new byte[18], AstroRateGattProtocol.cancel());
    }

    @Test
    public void rejectsValuesOutsideConsumerLimits() {
        SiderealRateCorrection correction =
                SiderealRateCorrection.fromBodyAngularVelocity(0.0, 1.0e-7, 0.0);

        assertThrows(IllegalArgumentException.class,
                () -> AstroRateGattProtocol.setpoint(correction, 0));
        assertThrows(IllegalArgumentException.class,
                () -> AstroRateGattProtocol.setpoint(correction, 65_536));
        SiderealRateCorrection zero =
                SiderealRateCorrection.fromBodyAngularVelocity(0.0, 0.0, 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> AstroRateGattProtocol.setpoint(zero, 1));
    }

    @Test
    public void v2UsesSeparateServiceAndRateUuids() {
        assertEquals(
                "7e6d7a50-2c56-4f17-9c51-9a5f64b02440",
                AstroRateGattProtocol.SERVICE_V2_UUID.toString());
        assertEquals(
                "7e6d7a50-2c56-4f17-9c51-9a5f64b02441",
                AstroRateGattProtocol.RATE_V2_UUID.toString());
        assertEquals(AstroRateGattProtocol.SERVICE_V2_UUID, AstroRateGattProtocol.SERVICE_UUID);
        assertEquals(AstroRateGattProtocol.RATE_V2_UUID, AstroRateGattProtocol.RATE_UUID);
    }

    @Test
    public void v2NegativeYawGoldenVectorIsExactLittleEndianQ16() {
        SiderealRateCorrection correction =
                SiderealRateCorrection.fromBodyAngularVelocity(
                        0.0, -SiderealRateCorrection.SIDEREAL_RATE_RAD_PER_SECOND, 0.0);

        assertArrayEquals(
                new byte[]{
                        (byte) 0xdb, (byte) 0xc9, 0x26, (byte) 0xfd,
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00,
                        (byte) 0xff, 0x0f,
                        0x00, 0x00, 0x00, 0x00},
                AstroRateGattProtocol.setpointV2(
                        correction, AstroRateGattProtocol.APP_TTL_TICKS));
    }

    @Test
    public void v2EncodesThreeSignedQ16LanesThenUnsignedTtl() {
        double unit = SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND;
        SiderealRateCorrection correction =
                SiderealRateCorrection.fromBodyAngularVelocity(
                        1.25 * unit,
                        -2.5 * unit,
                        0.125 * unit);

        assertArrayEquals(
                new byte[]{
                        0x00, (byte) 0x80, (byte) 0xfd, (byte) 0xff,
                        0x00, 0x40, 0x01, 0x00,
                        0x00, (byte) 0xe0, (byte) 0xff, (byte) 0xff,
                        0x34, 0x12,
                        0x00, 0x00, 0x00, 0x00},
                AstroRateGattProtocol.setpointV2(correction, 0x1234));
    }

    @Test
    public void v2CanRepresentASetpointBelowOneLegacyCount() {
        SiderealRateCorrection correction = SiderealRateCorrection.fromBodyAngularVelocity(
                SiderealRateCorrection.WIRE_Q16_UNIT_RAD_PER_SECOND, 0.0, 0.0);

        assertEquals(0, correction.getPitchCounts());
        assertEquals(1, correction.getPitchQ16());
        assertArrayEquals(
                new byte[]{
                        0x00, 0x00, 0x00, 0x00,
                        0x01, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00,
                        0x01, 0x00,
                        0x00, 0x00, 0x00, 0x00},
                AstroRateGattProtocol.setpointV2(correction, 1));
    }

    @Test
    public void v2CancelAndValidationAreExact() {
        assertArrayEquals(new byte[18], AstroRateGattProtocol.cancelV2());
        SiderealRateCorrection correction =
                SiderealRateCorrection.fromBodyAngularVelocity(1.0e-7, 0.0, 0.0);
        SiderealRateCorrection zero =
                SiderealRateCorrection.fromBodyAngularVelocity(0.0, 0.0, 0.0);

        assertThrows(
                IllegalArgumentException.class,
                () -> AstroRateGattProtocol.setpointV2(
                        (SiderealRateCorrection) null, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> AstroRateGattProtocol.setpointV2(correction, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> AstroRateGattProtocol.setpointV2(correction, 65_536));
        assertThrows(
                IllegalArgumentException.class,
                () -> AstroRateGattProtocol.setpointV2(zero, 1));
    }

    @Test
    public void v2RejectsUnsupportedGaveOffsetsWithOrWithoutBodyRate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AstroRateGattProtocol.setpointV2(
                        AstroRateSetpoint.gaveOnly(64, -64), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> AstroRateGattProtocol.setpointV2(
                        new AstroRateSetpoint(
                                SiderealRateCorrection.fromEquatorialAttitude(0.0, 0.0, 0.0),
                                0, 1), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> AstroRateSetpoint.gaveOnly(65, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> AstroRateGattProtocol.setpointV2(
                        AstroRateSetpoint.gaveOnly(0, 0), 1));
    }
}
