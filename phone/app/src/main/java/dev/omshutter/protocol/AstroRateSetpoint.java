package dev.omshutter.protocol;

import dev.omshutter.solver.SiderealRateCorrection;

import java.util.Objects;

/** Immutable Astro Rate v2 value. Legacy GAVE values are rejected by the active transport. */
public final class AstroRateSetpoint {
    public static final int MIN_GAVE_OFFSET = -64;
    public static final int MAX_GAVE_OFFSET = 64;

    private final SiderealRateCorrection bodyRate;
    private final int gave0Offset;
    private final int gave1Offset;

    public AstroRateSetpoint(
            SiderealRateCorrection bodyRate, int gave0Offset, int gave1Offset) {
        if (bodyRate == null) throw new IllegalArgumentException("body rate is null");
        this.bodyRate = bodyRate;
        this.gave0Offset = requireGave(gave0Offset, "GAVE lane 0");
        this.gave1Offset = requireGave(gave1Offset, "GAVE lane 1");
    }

    public static AstroRateSetpoint bodyOnly(SiderealRateCorrection bodyRate) {
        return new AstroRateSetpoint(bodyRate, 0, 0);
    }

    public static AstroRateSetpoint gaveOnly(int gave0Offset, int gave1Offset) {
        return new AstroRateSetpoint(
                SiderealRateCorrection.fromBodyAngularVelocity(0.0, 0.0, 0.0),
                gave0Offset,
                gave1Offset);
    }

    public SiderealRateCorrection getBodyRate() { return bodyRate; }
    public int getGave0Offset() { return gave0Offset; }
    public int getGave1Offset() { return gave1Offset; }

    public boolean hasBodyRate() {
        return bodyRate.getYawQ16() != 0
                || bodyRate.getPitchQ16() != 0
                || bodyRate.getRollQ16() != 0;
    }

    public boolean hasGaveOffset() { return gave0Offset != 0 || gave1Offset != 0; }
    public boolean isActive() { return hasBodyRate() || hasGaveOffset(); }

    private static int requireGave(int value, String field) {
        if (value < MIN_GAVE_OFFSET || value > MAX_GAVE_OFFSET) {
            throw new IllegalArgumentException(field + " is outside -64..64");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AstroRateSetpoint)) return false;
        AstroRateSetpoint that = (AstroRateSetpoint) other;
        return gave0Offset == that.gave0Offset
                && gave1Offset == that.gave1Offset
                && bodyRate.equals(that.bodyRate);
    }

    @Override
    public int hashCode() { return Objects.hash(bodyRate, gave0Offset, gave1Offset); }
}
