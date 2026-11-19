package dev.omshutter.protocol;

import dev.omshutter.solver.SiderealRateCorrection;

import java.util.UUID;

/** Pure encoder for mandatory Astro Rate v2, for the active camera service. */
public final class AstroRateGattProtocol {
    /** Signed-Q16.16 rate service. */
    public static final UUID SERVICE_V2_UUID =
            UUID.fromString("7e6d7a50-2c56-4f17-9c51-9a5f64b02440");
    /** Signed-Q16.16 rate characteristic. */
    public static final UUID RATE_V2_UUID =
            UUID.fromString("7e6d7a50-2c56-4f17-9c51-9a5f64b02441");

    /** Runtime aliases. */
    public static final UUID SERVICE_UUID = SERVICE_V2_UUID;
    public static final UUID RATE_UUID = RATE_V2_UUID;

    public static final int VALUE_V2_BYTES = 18;
    public static final int VALUE_BYTES = VALUE_V2_BYTES;
    public static final int MAX_TTL_TICKS = 0xffff;

    /** About 2.0475 s of body-correction hook time at the nominal ~2 kHz CPU0 seam. */
    public static final int APP_TTL_TICKS = 4_095;
    /** One serialized adaptive-or-exact active publication per nominal second. */
    public static final long APP_RENEWAL_MS = 1_000L;
    /** Fast serialized retry cadence for cancellation and local busy deferral. */
    public static final long APP_FAST_RETRY_MS = 50L;

    private AstroRateGattProtocol() {}

    /**
     * Encodes yaw, pitch, roll, and raw hook-tick TTL.
     *
     * <p>Rates are signed little-endian Q16.16 legacy counts whose raw unit is
     * {@code 1e-7 / 65536 rad/s}. TTL is an unsigned little-endian count of nominal 2 kHz
     * correction-hook ticks. An ordinary active admission snapshots the latest eligible cached
     * STAR STATE focus-breathed focal length; capture-time admissions use the same connection's
     * retained focal authority while the worker is busy. Firmware does not resample focal length
     * when it later consumes the mailbox, and focal length is not part of this GATT ABI.
     */
    public static byte[] setpoint(SiderealRateCorrection correction, int ttlTicks) {
        return setpointV2(correction, ttlTicks);
    }

    /** Exact all-zero cancellation value; no stale rate or TTL bytes are retained. */
    public static byte[] cancel() {
        return cancelV2();
    }

    /**
     * Encodes yaw, pitch, and roll as signed little-endian Q16.16 legacy counts, followed by the
     * unsigned little-endian raw hook-tick TTL and two zero GAVE lanes ({@code <iiiHhh>}).
     */
    public static byte[] setpointV2(SiderealRateCorrection correction, int ttlTicks) {
        return setpointV2(AstroRateSetpoint.bodyOnly(correction), ttlTicks);
    }

    /** Encodes an active v2 body rate; the final four bytes are reserved and must be zero. */
    public static byte[] setpointV2(AstroRateSetpoint setpoint, int ttlTicks) {
        if (setpoint == null) throw new IllegalArgumentException("Astro Rate setpoint is null");
        if (setpoint.hasGaveOffset()) {
            throw new IllegalArgumentException("GAVE lens correction is unsupported");
        }
        if (!setpoint.hasBodyRate()) {
            throw new IllegalArgumentException(
                    "active Astro Rate v2 setpoint has all-zero body lanes");
        }
        requireTtl(ttlTicks);

        SiderealRateCorrection correction = setpoint.getBodyRate();
        byte[] value = new byte[VALUE_V2_BYTES];
        putSigned32(value, 0, correction.getYawQ16());
        putSigned32(value, 4, correction.getPitchQ16());
        putSigned32(value, 8, correction.getRollQ16());
        putUnsigned16(value, 12, ttlTicks);
        return value;
    }

    /** Exact v2 all-zero cancellation value. */
    public static byte[] cancelV2() {
        return new byte[VALUE_V2_BYTES];
    }

    private static void putSigned32(byte[] value, int offset, int field) {
        value[offset] = (byte) field;
        value[offset + 1] = (byte) (field >>> 8);
        value[offset + 2] = (byte) (field >>> 16);
        value[offset + 3] = (byte) (field >>> 24);
    }

    private static void requireTtl(int ttlTicks) {
        if (ttlTicks < 1 || ttlTicks > MAX_TTL_TICKS) {
            throw new IllegalArgumentException("Astro Rate TTL is outside 1..65535 ticks");
        }
    }

    private static void putUnsigned16(byte[] value, int offset, int field) {
        value[offset] = (byte) field;
        value[offset + 1] = (byte) (field >>> 8);
    }
}
