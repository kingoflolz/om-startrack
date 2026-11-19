package dev.omshutter.protocol;

import dev.omshutter.solver.SiderealRateCorrection;

import java.util.UUID;

/**
 * Mandatory connection-scoped Astro Rate v2 wire format.
 *
 * <p>The enum remains as a narrow transport adapter for the BLE lease owner. There is no
 * discovery selection or legacy fallback.</p>
 */
public enum AstroRateTransport {
    V2(AstroRateGattProtocol.SERVICE_V2_UUID, AstroRateGattProtocol.RATE_V2_UUID);

    private final UUID serviceUuid;
    private final UUID rateUuid;

    AstroRateTransport(UUID serviceUuid, UUID rateUuid) {
        this.serviceUuid = serviceUuid;
        this.rateUuid = rateUuid;
    }

    public UUID getServiceUuid() {
        return serviceUuid;
    }

    public UUID getRateUuid() {
        return rateUuid;
    }

    public byte[] setpoint(SiderealRateCorrection correction, int ttlTicks) {
        return AstroRateGattProtocol.setpointV2(correction, ttlTicks);
    }

    public byte[] setpoint(AstroRateSetpoint setpoint, int ttlTicks) {
        return AstroRateGattProtocol.setpointV2(setpoint, ttlTicks);
    }

    public byte[] cancel() {
        return AstroRateGattProtocol.cancelV2();
    }

    /** Returns the exact physical rate represented by a successfully written transport value. */
    public SiderealRateCorrection acknowledgedRate(SiderealRateCorrection desired) {
        if (desired == null) throw new IllegalArgumentException("rate correction is null");
        return SiderealRateCorrection.fromQ16WireCounts(
                desired.getYawQ16(), desired.getPitchQ16(), desired.getRollQ16());
    }

    /** Canonical typed value represented by a successfully written v2 payload. */
    public AstroRateSetpoint acknowledgedSetpoint(AstroRateSetpoint desired) {
        if (desired == null) throw new IllegalArgumentException("rate setpoint is null");
        if (desired.hasGaveOffset()) {
            throw new IllegalArgumentException("GAVE lens correction is unsupported");
        }
        return AstroRateSetpoint.bodyOnly(acknowledgedRate(desired.getBodyRate()));
    }
}
