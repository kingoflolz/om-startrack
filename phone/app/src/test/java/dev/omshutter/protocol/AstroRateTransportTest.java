package dev.omshutter.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import dev.omshutter.solver.SiderealRateCorrection;

import org.junit.Test;

public final class AstroRateTransportTest {
    @Test
    public void transportExposesOnlyMandatoryV2Uuids() {
        assertEquals(
                AstroRateGattProtocol.SERVICE_V2_UUID,
                AstroRateTransport.V2.getServiceUuid());
        assertEquals(AstroRateGattProtocol.RATE_V2_UUID, AstroRateTransport.V2.getRateUuid());
    }

    @Test
    public void selectedTransportOwnsSetpointAndCancelEncoding() {
        SiderealRateCorrection correction =
                SiderealRateCorrection.fromEquatorialAttitude(0.0, 0.0, 0.0);

        assertArrayEquals(
                AstroRateGattProtocol.setpointV2(correction, 123),
                AstroRateTransport.V2.setpoint(correction, 123));
        assertArrayEquals(new byte[18], AstroRateTransport.V2.cancel());
    }

    @Test
    public void acknowledgedModelReflectsSelectedWirePrecision() {
        SiderealRateCorrection desired =
                SiderealRateCorrection.fromEquatorialAttitude(0.0, 0.0, 0.0);

        assertEquals(desired, AstroRateTransport.V2.acknowledgedRate(desired));
    }

    @Test
    public void acknowledgedModelCanonicalizesFieldsNotPresentOnV2Wire() {
        double justBelowLegacyHalf = (0.5 - 0.25 / SiderealRateCorrection.WIRE_Q16_SCALE)
                * SiderealRateCorrection.WIRE_UNIT_RAD_PER_SECOND;
        SiderealRateCorrection desired = SiderealRateCorrection.fromBodyAngularVelocity(
                justBelowLegacyHalf, 0.0, 0.0);
        SiderealRateCorrection acknowledged = AstroRateTransport.V2.acknowledgedRate(desired);

        assertEquals(desired.getPitchQ16(), acknowledged.getPitchQ16());
        assertNotEquals(desired.getPitchCounts(), acknowledged.getPitchCounts());
        assertEquals(1, acknowledged.getPitchCounts());
    }

    @Test
    public void unsupportedGaveCannotBeSentOrAcknowledged() {
        AstroRateSetpoint desired = new AstroRateSetpoint(
                SiderealRateCorrection.fromEquatorialAttitude(0.0, 0.0, 0.0),
                -64,
                23);

        assertThrows(IllegalArgumentException.class,
                () -> AstroRateTransport.V2.setpoint(desired, 321));
        assertThrows(IllegalArgumentException.class,
                () -> AstroRateTransport.V2.acknowledgedSetpoint(desired));
    }
}
