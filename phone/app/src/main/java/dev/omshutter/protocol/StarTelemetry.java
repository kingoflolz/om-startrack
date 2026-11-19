package dev.omshutter.protocol;

import java.util.Objects;

/** Immutable detector configuration and IBIS telemetry used by STAR STATE. */
public final class StarTelemetry {
    public static final int IBIS_FLAG_SOURCE_MAPPED = 1 << 0;
    public static final int IBIS_FLAG_MIRROR_ENABLED = 1 << 1;
    public static final int IBIS_FLAG_DOUBLE_READ_STABLE = 1 << 2;
    public static final int IBIS_FLAG_OBSERVED_CHANGE_SINCE_ENABLE = 1 << 3;
    public static final int IBIS_KNOWN_FLAGS = IBIS_FLAG_SOURCE_MAPPED
            | IBIS_FLAG_MIRROR_ENABLED
            | IBIS_FLAG_DOUBLE_READ_STABLE
            | IBIS_FLAG_OBSERVED_CHANGE_SINCE_ENABLE;
    public static final int IBIS_Q10_FULL_SCALE = 1024;
    public static final DetectorConfig DEFAULT_CONFIG = new DetectorConfig(
            5 * 256, 3, 1024, 3 * 256, 230, 1);

    private StarTelemetry() {}

    public static final class DetectorConfig {
        private final int sigmaQ8;
        private final int minArea;
        private final int maxArea;
        private final int maxElongationQ8;
        private final int maxSharpnessQ8;
        private final int borderMargin;

        public DetectorConfig(
                int sigmaQ8,
                int minArea,
                int maxArea,
                int maxElongationQ8,
                int maxSharpnessQ8,
                int borderMargin) {
            this.sigmaQ8 = u16(sigmaQ8, "sigma Q8");
            this.minArea = u16(minArea, "minimum area");
            this.maxArea = u16(maxArea, "maximum area");
            this.maxElongationQ8 = u16(maxElongationQ8, "maximum elongation Q8");
            this.maxSharpnessQ8 = u16(maxSharpnessQ8, "maximum sharpness Q8");
            this.borderMargin = u16(borderMargin, "border margin");
            if (sigmaQ8 < 1 || sigmaQ8 > 16_384) {
                throw new IllegalArgumentException("sigma Q8 is outside 1..16384");
            }
            if (minArea < 1) {
                throw new IllegalArgumentException("minimum area must be at least 1");
            }
            if (minArea > maxArea) {
                throw new IllegalArgumentException("minimum area exceeds maximum area");
            }
            if (maxElongationQ8 != 0 && maxElongationQ8 < 256) {
                throw new IllegalArgumentException(
                        "maximum elongation Q8 must be zero or at least 256");
            }
            if (maxSharpnessQ8 > 256) {
                throw new IllegalArgumentException("maximum sharpness Q8 exceeds 256");
            }
            if (borderMargin > 1943) {
                throw new IllegalArgumentException("border margin exceeds 1943");
            }
        }

        public int getSigmaQ8() { return sigmaQ8; }
        public int getMinArea() { return minArea; }
        public int getMaxArea() { return maxArea; }
        public int getMaxElongationQ8() { return maxElongationQ8; }
        public int getMaxSharpnessQ8() { return maxSharpnessQ8; }
        public int getBorderMargin() { return borderMargin; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof DetectorConfig)) return false;
            DetectorConfig that = (DetectorConfig) other;
            return sigmaQ8 == that.sigmaQ8
                    && minArea == that.minArea
                    && maxArea == that.maxArea
                    && maxElongationQ8 == that.maxElongationQ8
                    && maxSharpnessQ8 == that.maxSharpnessQ8
                    && borderMargin == that.borderMargin;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    sigmaQ8, minArea, maxArea, maxElongationQ8,
                    maxSharpnessQ8, borderMargin);
        }
    }

    public static final class IbisSample {
        private final int flags;
        private final int linuxSequence;
        private final int rawX;
        private final int rawY;
        private final int rawRoll;

        public IbisSample(
                int flags, int linuxSequence, int rawX, int rawY, int rawRoll) {
            if ((flags & ~IBIS_KNOWN_FLAGS) != 0) {
                throw new IllegalArgumentException("IBIS telemetry has unknown flags");
            }
            boolean sourceMapped = (flags & IBIS_FLAG_SOURCE_MAPPED) != 0;
            boolean mirrorEnabled = (flags & IBIS_FLAG_MIRROR_ENABLED) != 0;
            boolean doubleReadStable = (flags & IBIS_FLAG_DOUBLE_READ_STABLE) != 0;
            boolean observedChange =
                    (flags & IBIS_FLAG_OBSERVED_CHANGE_SINCE_ENABLE) != 0;
            if (mirrorEnabled && !sourceMapped) {
                throw new IllegalArgumentException(
                        "IBIS mirror-enabled flag requires a mapped source");
            }
            if (doubleReadStable && (!sourceMapped || !mirrorEnabled)) {
                throw new IllegalArgumentException(
                        "IBIS double-read-stable flag requires a mapped, enabled mirror");
            }
            if (observedChange && (!sourceMapped || !mirrorEnabled)) {
                throw new IllegalArgumentException(
                        "IBIS observed-change flag requires a mapped, enabled mirror");
            }
            this.flags = flags;
            this.linuxSequence = u16(linuxSequence, "Linux sequence");
            this.rawX = q10(rawX, "raw X");
            this.rawY = q10(rawY, "raw Y");
            this.rawRoll = q10(rawRoll, "raw roll");
        }

        public int getFlags() { return flags; }
        public int getLinuxSequence() { return linuxSequence; }
        public int getRawX() { return rawX; }
        public int getRawY() { return rawY; }
        public int getRawRoll() { return rawRoll; }
        public boolean isSourceMapped() {
            return (flags & IBIS_FLAG_SOURCE_MAPPED) != 0;
        }
        public boolean isMirrorEnabled() {
            return (flags & IBIS_FLAG_MIRROR_ENABLED) != 0;
        }
        public boolean isDoubleReadStable() {
            return (flags & IBIS_FLAG_DOUBLE_READ_STABLE) != 0;
        }
        public boolean hasObservedChangeSinceEnable() {
            return (flags & IBIS_FLAG_OBSERVED_CHANGE_SINCE_ENABLE) != 0;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IbisSample)) return false;
            IbisSample that = (IbisSample) other;
            return flags == that.flags
                    && linuxSequence == that.linuxSequence
                    && rawX == that.rawX && rawY == that.rawY && rawRoll == that.rawRoll;
        }

        @Override
        public int hashCode() {
            return Objects.hash(flags, linuxSequence, rawX, rawY, rawRoll);
        }
    }

    /**
     * Validate a shared unsigned telemetry field.
     * Values outside the wire range are rejected.
     */
    private static int u16(int value, String label) {
        if (value < 0 || value > 0xffff) {
            throw new IllegalArgumentException(label + " does not fit uint16");
        }
        return value;
    }

    private static int signed16(int value, String label) {
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw new IllegalArgumentException(label + " does not fit int16");
        }
        return value;
    }

    private static int q10(int value, String label) {
        signed16(value, label);
        if (value < -IBIS_Q10_FULL_SCALE || value > IBIS_Q10_FULL_SCALE) {
            throw new IllegalArgumentException(label + " is outside normalized Q10 full-scale");
        }
        return value;
    }

}
