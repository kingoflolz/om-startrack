package dev.omshutter.protocol;

/** Decoder for the gyro attitude trailer embedded in STAR STATE v7. */
public final class GyroAttitudeProtocol {
    public static final int VERSION = 1;
    public static final int VALUE_BYTES = 48;
    public static final int TICK_HZ = 2_000;
    public static final int FLAG_CURRENT_VALID = 1;
    public static final int FLAG_CAPTURE_VALID = 2;
    public static final int FLAG_CAPTURE_PREPARATION = 4;
    public static final int KNOWN_FLAGS = 7;
    private static final double Q30_SCALE = 1_073_741_824.0;
    private static final double MIN_NORM_SQUARED = 0.98 * 0.98;
    private static final double MAX_NORM_SQUARED = 1.02 * 1.02;

    private GyroAttitudeProtocol() {}

    public static Snapshot parse(byte[] value) throws ProtocolException {
        if (value == null || value.length != VALUE_BYTES) {
            throw new ProtocolException("attitude length must be 48 bytes");
        }
        if ((value[0] & 0xff) != VERSION) {
            throw new ProtocolException("unsupported attitude version");
        }
        int flags = value[1] & 0xff;
        if ((flags & ~KNOWN_FLAGS) != 0) {
            throw new ProtocolException("unknown attitude flags");
        }
        if (value[2] != 0 || value[3] != 0) {
            throw new ProtocolException("nonzero attitude reserved bytes");
        }
        if ((flags & FLAG_CAPTURE_PREPARATION) != 0 && (flags & FLAG_CAPTURE_VALID) == 0) {
            throw new ProtocolException("capture preparation requires a valid capture");
        }
        int[] current = (flags & FLAG_CURRENT_VALID) != 0
                ? readQuaternion(value, 16, "current") : null;
        int[] capture = (flags & FLAG_CAPTURE_VALID) != 0
                ? readQuaternion(value, 32, "capture") : null;
        return new Snapshot(flags, readUnsigned32(value, 4), readUnsigned32(value, 8),
                readUnsigned32(value, 12), current, capture);
    }

    private static int[] readQuaternion(byte[] value, int offset, String name)
            throws ProtocolException {
        int[] quaternion = new int[4];
        double normSquared = 0;
        for (int index = 0; index < 4; index++) {
            quaternion[index] = (int) readUnsigned32(value, offset + index * 4);
            double component = quaternion[index] / Q30_SCALE;
            normSquared += component * component;
        }
        if (normSquared < MIN_NORM_SQUARED || normSquared > MAX_NORM_SQUARED) {
            throw new ProtocolException(name + " quaternion norm is outside 0.98..1.02");
        }
        return quaternion;
    }

    private static long readUnsigned32(byte[] value, int offset) {
        return (value[offset] & 0xffL) | ((value[offset + 1] & 0xffL) << 8)
                | ((value[offset + 2] & 0xffL) << 16) | ((value[offset + 3] & 0xffL) << 24);
    }

    /**
     * Coherent live and retained capture samples in the integration's fixed reference.
     * Tick and capture sequence counters are unsigned 32-bit values and wrap modulo 2^32.
     * Quaternions are Hamilton scalar-first (w,x,y,z), mapping body to the boot reference.
     */
    public static final class Snapshot {
        private final int flags;
        private final long tick;
        private final long captureSequence;
        private final long captureTick;
        private final int[] currentQ30;
        private final int[] captureQ30;

        private Snapshot(int flags, long tick, long captureSequence,
                long captureTick, int[] currentQ30, int[] captureQ30) {
            this.flags = flags;
            this.tick = tick;
            this.captureSequence = captureSequence;
            this.captureTick = captureTick;
            this.currentQ30 = currentQ30;
            this.captureQ30 = captureQ30;
        }

        public int getFlags() { return flags; }
        public long getTick() { return tick; }
        public long getCaptureSequence() { return captureSequence; }
        public long getCaptureTick() { return captureTick; }
        public boolean isCurrentValid() { return (flags & FLAG_CURRENT_VALID) != 0; }
        public boolean isCaptureValid() { return (flags & FLAG_CAPTURE_VALID) != 0; }
        public boolean isCaptureTimingPreparation() {
            return (flags & FLAG_CAPTURE_PREPARATION) != 0;
        }
        public int[] getCurrentQ30() { return copy(currentQ30); }
        public int[] getCaptureQ30() { return copy(captureQ30); }
        public double[] getCurrentQuaternion() { return normalized(currentQ30); }
        public double[] getCaptureQuaternion() { return normalized(captureQ30); }

        private static int[] copy(int[] value) { return value == null ? null : value.clone(); }

        private static double[] normalized(int[] value) {
            if (value == null) return null;
            double[] result = new double[4];
            double normSquared = 0;
            for (int index = 0; index < 4; index++) {
                result[index] = value[index] / Q30_SCALE;
                normSquared += result[index] * result[index];
            }
            double norm = Math.sqrt(normSquared);
            for (int index = 0; index < 4; index++) result[index] /= norm;
            return result;
        }
    }

    public static final class ProtocolException extends Exception {
        public ProtocolException(String message) { super(message); }
    }
}
