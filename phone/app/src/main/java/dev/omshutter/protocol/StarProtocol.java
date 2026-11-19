package dev.omshutter.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Current STAR configuration, catalog, capture, and attitude STATE codec. */
public final class StarProtocol {
    public static final UUID SERVICE_UUID =
            UUID.fromString("7e6d7a50-2c56-4f17-9c51-9a5f64b02450");
    public static final UUID CONTROL_UUID =
            UUID.fromString("7e6d7a50-2c56-4f17-9c51-9a5f64b02451");
    public static final UUID STATE_UUID =
            UUID.fromString("7e6d7a50-2c56-4f17-9c51-9a5f64b02452");
    public static final UUID SHUTTER_CATALOG_UUID =
            UUID.fromString("7e6d7a50-2c56-4f17-9c51-9a5f64b02453");
    public static final UUID APERTURE_CATALOG_UUID =
            UUID.fromString("7e6d7a50-2c56-4f17-9c51-9a5f64b02454");
    public static final UUID ISO_CATALOG_UUID =
            UUID.fromString("7e6d7a50-2c56-4f17-9c51-9a5f64b02455");
    public static final UUID LEASE_UUID =
            UUID.fromString("7e6d7a50-2c56-4f17-9c51-9a5f64b02456");
    public static final UUID CLIENT_CONFIGURATION_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final int CONFIG_VERSION = 6;
    public static final int MINIMUM_ATT_MTU = 160;
    public static final int PREFERRED_ATT_MTU = 517;
    public static final int STATE_WRITE_BYTES = 27;
    public static final int STATE_PREFIX_BYTES = 77;
    public static final int STATE_VERSION = 7;
    public static final int STATE_FIXED_BYTES = STATE_PREFIX_BYTES + GyroAttitudeProtocol.VALUE_BYTES;
    public static final int STATE_MAX_BYTES = STATE_FIXED_BYTES + 32;
    public static final int MAX_LENS_NAME_BYTES = 32;
    public static final int MAX_CATALOG_VALUE_BYTES = 512;
    public static final int MAX_CATALOG_OPTIONS = 128;
    public static final int UNAVAILABLE_INDEX = 0xff;
    public static final int FLAG_CATALOG_AVAILABLE = 1 << 0;
    public static final int FLAG_ACTUAL_AVAILABLE = 1 << 1;
    public static final int FLAG_RECIPE_AVAILABLE = 1 << 2;
    public static final int FLAG_DETECTOR_AVAILABLE = 1 << 3;
    public static final int FLAG_IBIS_AVAILABLE = 1 << 4;
    public static final int FLAG_ACCELERATION_AVAILABLE = 1 << 5;
    public static final int FLAG_RECOVERY_TARGET = 1 << 7;
    public static final int STATE_KNOWN_FLAGS = FLAG_CATALOG_AVAILABLE
            | FLAG_ACTUAL_AVAILABLE
            | FLAG_RECIPE_AVAILABLE
            | FLAG_DETECTOR_AVAILABLE
            | FLAG_IBIS_AVAILABLE
            | FLAG_ACCELERATION_AVAILABLE
            | FLAG_RECOVERY_TARGET;

    public static final int IBIS_LENS_COMMAND_SOURCE_MAPPED = 1 << 4;
    public static final int IBIS_LENS_COMMAND_SNAPSHOT_VALID = 1 << 5;
    public static final int IBIS_KNOWN_FLAGS = StarTelemetry.IBIS_KNOWN_FLAGS
            | IBIS_LENS_COMMAND_SOURCE_MAPPED
            | IBIS_LENS_COMMAND_SNAPSHOT_VALID;

    public static final int ACCELERATION_FLAG_SOURCE_MAPPED = 1 << 0;
    public static final int ACCELERATION_FLAG_LAYOUT_QUALIFIED = 1 << 1;
    public static final int ACCELERATION_FLAG_SNAPSHOT_COHERENT = 1 << 2;
    public static final int ACCELERATION_FLAG_SOURCE_FRESH = 1 << 3;
    public static final int ACCELERATION_KNOWN_FLAGS = 0x0f;
    public static final int ACCELERATION_VALID_FLAGS = 0x0f;

    public static final int LEASE_MAX_SECONDS = 120;
    public static final int PHONE_LEASE_SECONDS = 45;
    public static final long PHONE_LEASE_RENEWAL_MS = 15_000L;

    private StarProtocol() {}

    public static byte[] captureRecipeRequest() {
        return new byte[]{0x01};
    }

    public static byte[] controlSubscriptionValue() {
        return new byte[]{0x02, 0x00};
    }

    public static byte[] stateSubscriptionValue() {
        return new byte[]{0x01, 0x00};
    }

    public static byte[] encodeLeaseSeconds(int seconds) {
        if (seconds < 0 || seconds > LEASE_MAX_SECONDS) {
            throw new IllegalArgumentException("STAR lease is outside 0..120 seconds");
        }
        return new byte[]{(byte) seconds, (byte) (seconds >>> 8)};
    }

    /** Parses one compact catalog. Count zero is the contract's readable unavailable value. */
    public static List<ExposureCatalogs.Option> parseExposureCatalog(
            ExposureCatalogs.Kind kind, byte[] value) throws ProtocolException {
        if (kind == null) throw new ProtocolException("catalog kind is null");
        if (value == null || value.length < 1 || value.length > MAX_CATALOG_VALUE_BYTES) {
            throw new ProtocolException("catalog length is outside 1..512 bytes");
        }
        int count = u8(value[0]);
        if (count > MAX_CATALOG_OPTIONS) {
            throw new ProtocolException("catalog count exceeds 128");
        }
        if (count == 0) {
            if (value.length != 1) {
                throw new ProtocolException("unavailable catalog has trailing bytes");
            }
            return Collections.emptyList();
        }
        int offset = 1;
        ArrayList<ExposureCatalogs.Option> options = new ArrayList<>(count);
        Set<String> labels = new HashSet<>();
        for (int index = 0; index < count; index++) {
            if (offset >= value.length) throw new ProtocolException("catalog label is truncated");
            int length = u8(value[offset++]);
            if (length < 1 || length > 31 || offset + length > value.length) {
                throw new ProtocolException("catalog label length is invalid");
            }
            String label = printableAscii(value, offset, length, 0x21, "catalog label");
            offset += length;
            if (!labels.add(label)) {
                throw new ProtocolException("catalog contains a duplicate label");
            }
            options.add(new ExposureCatalogs.Option(kind, label));
        }
        if (offset != value.length) throw new ProtocolException("catalog has trailing bytes");
        return Collections.unmodifiableList(options);
    }

    /** Parses STATE, including its required gyro attitude trailer. */
    public static State parseState(byte[] value) throws ProtocolException {
        if (value == null || value.length < STATE_FIXED_BYTES || value.length > STATE_MAX_BYTES) {
            throw new ProtocolException("STATE length is outside supported bounds");
        }
        int version = u8(value[0]);
        if (version != STATE_VERSION) {
            throw new ProtocolException("unsupported STATE version");
        }
        int lensLength = u8(value[76]);
        int fixedLength = STATE_FIXED_BYTES;
        if (lensLength > MAX_LENS_NAME_BYTES || value.length != fixedLength + lensLength) {
            throw new ProtocolException("STATE lens length does not match the value");
        }
        GyroAttitudeProtocol.Snapshot gyroAttitude = null;
        {
            try {
                gyroAttitude = GyroAttitudeProtocol.parse(Arrays.copyOfRange(
                        value, STATE_PREFIX_BYTES + lensLength, value.length));
            } catch (GyroAttitudeProtocol.ProtocolException error) {
                throw new ProtocolException("STATE gyro attitude trailer is invalid", error);
            }
        }

        int flags = u8(value[1]);
        if ((flags & ~STATE_KNOWN_FLAGS) != 0) {
            throw new ProtocolException("STATE has unknown flags");
        }
        long publicationGeneration = u32(value, 2);
        long configGeneration = u32(value, 6);
        long catalogGeneration = u32(value, 10);
        long workerGeneration = u32(value, 14);
        int[] actualNative = i16x3(value, 18);
        IndexTuple actual;
        IndexTuple recipe;
        try {
            actual = new IndexTuple(u8(value[24]), u8(value[25]), u8(value[26]), true);
            recipe = new IndexTuple(u8(value[27]), u8(value[28]), u8(value[29]), true);
        } catch (IllegalArgumentException error) {
            throw new ProtocolException("STATE contains an invalid catalog index", error);
        }
        int apertureMinimum = u8(value[30]);
        int apertureMaximum = u8(value[31]);
        int focalBits = (int) u32(value, 32);
        validateFocal(focalBits);

        int[] detectorWords = u16x6(value, 36);
        StarTelemetry.DetectorConfig detector = null;
        if ((flags & FLAG_DETECTOR_AVAILABLE) != 0) {
            try {
                detector = detectorFromWords(detectorWords);
            } catch (IllegalArgumentException error) {
                throw new ProtocolException("STATE detector configuration is invalid", error);
            }
        } else if (!allZero(detectorWords)) {
            throw new ProtocolException("unavailable STATE detector fields are nonzero");
        }

        int ibisFlags = u8(value[48]);
        int ibisSequence = u16(value, 49);
        int[] ibisLanes = i16x3(value, 51);
        long lensCommandGeneration = u32(value, 57);
        int lensMovestep0Raw = i16(value, 61);
        int lensMovestep1Raw = i16(value, 63);
        if ((ibisFlags & ~IBIS_KNOWN_FLAGS) != 0) {
            throw new ProtocolException("STATE IBIS block has unknown flags");
        }
        int q10Flags = ibisFlags & StarTelemetry.IBIS_KNOWN_FLAGS;
        boolean q10SourceMapped =
                (q10Flags & StarTelemetry.IBIS_FLAG_SOURCE_MAPPED) != 0;
        StarTelemetry.IbisSample ibis = null;
        if (q10SourceMapped) {
            try {
                ibis = new StarTelemetry.IbisSample(
                        q10Flags, ibisSequence, ibisLanes[0], ibisLanes[1], ibisLanes[2]);
            } catch (IllegalArgumentException error) {
                throw new ProtocolException("STATE IBIS Q10 block is invalid", error);
            }
        } else if (q10Flags != 0 || ibisSequence != 0 || !allZero(ibisLanes)) {
            throw new ProtocolException("unmapped STATE IBIS Q10 fields are nonzero");
        }
        LensCommandSnapshot lensCommand;
        try {
            lensCommand = new LensCommandSnapshot(
                    (ibisFlags & IBIS_LENS_COMMAND_SOURCE_MAPPED) != 0,
                    (ibisFlags & IBIS_LENS_COMMAND_SNAPSHOT_VALID) != 0,
                    lensCommandGeneration,
                    lensMovestep0Raw,
                    lensMovestep1Raw);
        } catch (IllegalArgumentException error) {
            throw new ProtocolException("STATE latest lens-command block is invalid", error);
        }
        boolean ibisAvailable = (flags & FLAG_IBIS_AVAILABLE) != 0;
        if (ibisAvailable != (q10SourceMapped || lensCommand.isSourceMapped())) {
            throw new ProtocolException("STATE IBIS availability disagrees with its sources");
        }

        int accelerationFlags = u8(value[65]);
        long accelerationMainGeneration = u32(value, 66);
        int[] accelerationLanes = i16x3(value, 70);
        Acceleration acceleration = null;
        if ((flags & FLAG_ACCELERATION_AVAILABLE) != 0) {
            try {
                acceleration = new Acceleration(
                        accelerationFlags,
                        accelerationMainGeneration,
                        accelerationLanes[0],
                        accelerationLanes[1],
                        accelerationLanes[2]);
                if (!acceleration.isSourceMapped()) {
                    throw new IllegalArgumentException(
                            "STATE acceleration availability requires a mapped source");
                }
            } catch (IllegalArgumentException error) {
                throw new ProtocolException("STATE acceleration block is invalid", error);
            }
        } else if (accelerationFlags != 0
                || accelerationMainGeneration != 0L
                || !allZero(accelerationLanes)) {
            throw new ProtocolException("unavailable STATE acceleration fields are nonzero");
        }

        String lens = printableAscii(value, 77, lensLength, 0x20, "STATE lens name");

        boolean catalogsAvailable = (flags & FLAG_CATALOG_AVAILABLE) != 0;
        boolean actualAvailable = (flags & FLAG_ACTUAL_AVAILABLE) != 0;
        boolean recipeAvailable = (flags & FLAG_RECIPE_AVAILABLE) != 0;
        boolean detectorAvailable = (flags & FLAG_DETECTOR_AVAILABLE) != 0;
        if (catalogsAvailable != (catalogGeneration != 0L)) {
            throw new ProtocolException("STATE catalog flag and generation disagree");
        }
        boolean completeConfig = actualAvailable && recipeAvailable && detectorAvailable;
        if (completeConfig != (configGeneration != 0L)) {
            throw new ProtocolException(
                    "STATE config generation and mutable-field completeness disagree");
        }
        if (actualAvailable) {
            if (workerGeneration == 0L) {
                throw new ProtocolException("STATE actual fields require a worker generation");
            }
            actual.requireAvailable("actual");
            requireIndex(apertureMinimum, "aperture minimum");
            requireIndex(apertureMaximum, "aperture maximum");
            if (apertureMinimum > apertureMaximum
                    || actual.aperture < apertureMinimum
                    || actual.aperture > apertureMaximum) {
                throw new ProtocolException("STATE actual aperture is outside its bounds");
            }
        } else {
            actual.requireUnavailable("actual");
            if (!allZero(actualNative) || focalBits != 0
                    || apertureMinimum != UNAVAILABLE_INDEX
                    || apertureMaximum != UNAVAILABLE_INDEX
                    || lensLength != 0) {
                throw new ProtocolException("unavailable STATE actual fields are not canonical");
            }
        }
        if (recipeAvailable) {
            recipe.requireAvailable("recipe");
            if (actualAvailable
                    && (recipe.aperture < apertureMinimum
                        || recipe.aperture > apertureMaximum)) {
                throw new ProtocolException("STATE recipe aperture is outside actual bounds");
            }
        } else {
            recipe.requireUnavailable("recipe");
        }
        if ((actualAvailable || recipeAvailable) && !catalogsAvailable) {
            throw new ProtocolException("STATE indexed settings require a catalog bank");
        }
        if ((flags & FLAG_RECOVERY_TARGET) != 0 && !actualAvailable) {
            throw new ProtocolException("STATE recovery target requires actual fields");
        }

        return new State(
                flags,
                publicationGeneration,
                configGeneration,
                catalogGeneration,
                workerGeneration,
                actualNative,
                actual,
                recipe,
                apertureMinimum,
                apertureMaximum,
                focalBits,
                detector,
                ibis,
                lensCommand,
                acceleration,
                lens,
                gyroAttitude);
    }

    public static MutationTarget target(
            State authoritative,
            ExposureCatalogs catalogs,
            ExposureCatalogs.Selection live,
            ExposureCatalogs.Selection recipe,
            StarTelemetry.DetectorConfig detector) {
        if (authoritative == null || !authoritative.hasCompleteMutableState()) {
            throw new IllegalArgumentException("authoritative STAR state is incomplete");
        }
        if (catalogs == null) throw new IllegalArgumentException("catalogs are unavailable");
        IndexTuple liveIndices = indicesFor(catalogs, live);
        IndexTuple recipeIndices = indicesFor(catalogs, recipe);
        if (liveIndices.aperture < authoritative.apertureMinimum
                || liveIndices.aperture > authoritative.apertureMaximum
                || recipeIndices.aperture < authoritative.apertureMinimum
                || recipeIndices.aperture > authoritative.apertureMaximum) {
            throw new IllegalArgumentException("requested aperture is outside current bounds");
        }
        return new MutationTarget(
                authoritative.configGeneration,
                authoritative.catalogGeneration,
                liveIndices,
                recipeIndices,
                detector);
    }

    public static byte[] encodeStateWrite(MutationTarget target) {
        if (target == null) throw new IllegalArgumentException("STATE target is null");
        byte[] value = new byte[STATE_WRITE_BYTES];
        value[0] = CONFIG_VERSION;
        putU32(value, 1, target.expectedConfigGeneration);
        putU32(value, 5, target.expectedCatalogGeneration);
        target.live.put(value, 9);
        target.recipe.put(value, 12);
        putDetector(value, 15, target.detector);
        return value;
    }

    public static ResolvedState resolveActual(State state, ExposureCatalogs catalogs) {
        if (state == null || !state.hasActualAvailable() || catalogs == null) return null;
        return new ResolvedState(state, catalogs);
    }

    public static ExposureCatalogs.Selection resolveRecipe(
            State state, ExposureCatalogs catalogs) {
        if (state == null || !state.hasRecipeAvailable() || catalogs == null) return null;
        return selectionAt(catalogs, state.recipe);
    }

    private static ExposureCatalogs.Selection selectionAt(
            ExposureCatalogs catalogs, IndexTuple indices) {
        try {
            return new ExposureCatalogs.Selection(
                    catalogs.get(ExposureCatalogs.Kind.SHUTTER).get(indices.shutter).getLabel(),
                    catalogs.get(ExposureCatalogs.Kind.APERTURE).get(indices.aperture).getLabel(),
                    catalogs.get(ExposureCatalogs.Kind.ISO).get(indices.iso).getLabel());
        } catch (IndexOutOfBoundsException error) {
            throw new IllegalArgumentException("STATE index is outside the coherent catalog", error);
        }
    }

    private static IndexTuple indicesFor(
            ExposureCatalogs catalogs, ExposureCatalogs.Selection selection) {
        if (selection == null || !catalogs.contains(selection)) {
            throw new IllegalArgumentException("selection is outside the coherent catalog");
        }
        return new IndexTuple(
                indexOf(catalogs, ExposureCatalogs.Kind.SHUTTER, selection.getShutter()),
                indexOf(catalogs, ExposureCatalogs.Kind.APERTURE, selection.getAperture()),
                indexOf(catalogs, ExposureCatalogs.Kind.ISO, selection.getIso()),
                false);
    }

    private static int indexOf(
            ExposureCatalogs catalogs, ExposureCatalogs.Kind kind, String label) {
        List<ExposureCatalogs.Option> options = catalogs.get(kind);
        for (int index = 0; index < options.size(); index++) {
            if (label.equals(options.get(index).getLabel())) return index;
        }
        throw new IllegalArgumentException("selection label is absent from catalog");
    }

    public static final class MutationTarget {
        private final long expectedConfigGeneration;
        private final long expectedCatalogGeneration;
        private final IndexTuple live;
        private final IndexTuple recipe;
        private final StarTelemetry.DetectorConfig detector;

        private MutationTarget(
                long expectedConfigGeneration,
                long expectedCatalogGeneration,
                IndexTuple live,
                IndexTuple recipe,
                StarTelemetry.DetectorConfig detector) {
            if (expectedConfigGeneration <= 0L || expectedConfigGeneration > 0xffff_ffffL
                    || expectedCatalogGeneration <= 0L
                    || expectedCatalogGeneration > 0xffff_ffffL) {
                throw new IllegalArgumentException("STATE CAS generations must be nonzero u32");
            }
            if (detector == null) throw new IllegalArgumentException("detector target is null");
            this.expectedConfigGeneration = expectedConfigGeneration;
            this.expectedCatalogGeneration = expectedCatalogGeneration;
            this.live = live;
            this.recipe = recipe;
            this.detector = detector;
        }

        public long getExpectedConfigGeneration() { return expectedConfigGeneration; }
        public long getExpectedCatalogGeneration() { return expectedCatalogGeneration; }
        public int getLiveShutterIndex() { return live.shutter; }
        public int getLiveApertureIndex() { return live.aperture; }
        public int getLiveIsoIndex() { return live.iso; }
        public int getRecipeShutterIndex() { return recipe.shutter; }
        public int getRecipeApertureIndex() { return recipe.aperture; }
        public int getRecipeIsoIndex() { return recipe.iso; }
        public StarTelemetry.DetectorConfig getDetector() { return detector; }

        /** Semantic completion requires a later authoritative matching STATE in the same bank. */
        public boolean matches(State state) {
            return state != null
                    && state.hasCompleteMutableState()
                    && !state.isRecoveryTarget()
                    && state.catalogGeneration == expectedCatalogGeneration
                    && live.equals(state.actual)
                    && recipe.equals(state.recipe)
                    && detector.equals(state.detector);
        }
    }

    public static final class State {
        private final int flags;
        private final long publicationGeneration;
        private final long configGeneration;
        private final long catalogGeneration;
        private final long workerGeneration;
        private final int[] actualNative;
        private final IndexTuple actual;
        private final IndexTuple recipe;
        private final int apertureMinimum;
        private final int apertureMaximum;
        private final int focalLengthBits;
        private final StarTelemetry.DetectorConfig detector;
        private final StarTelemetry.IbisSample ibis;
        private final LensCommandSnapshot lensCommand;
        private final Acceleration acceleration;
        private final String lensName;
        private final GyroAttitudeProtocol.Snapshot gyroAttitude;

        private State(
                int flags,
                long publicationGeneration,
                long configGeneration,
                long catalogGeneration,
                long workerGeneration,
                int[] actualNative,
                IndexTuple actual,
                IndexTuple recipe,
                int apertureMinimum,
                int apertureMaximum,
                int focalLengthBits,
                StarTelemetry.DetectorConfig detector,
                StarTelemetry.IbisSample ibis,
                LensCommandSnapshot lensCommand,
                Acceleration acceleration,
                String lensName,
                GyroAttitudeProtocol.Snapshot gyroAttitude) {
            this.flags = flags;
            this.publicationGeneration = publicationGeneration;
            this.configGeneration = configGeneration;
            this.catalogGeneration = catalogGeneration;
            this.workerGeneration = workerGeneration;
            this.actualNative = actualNative.clone();
            this.actual = actual;
            this.recipe = recipe;
            this.apertureMinimum = apertureMinimum;
            this.apertureMaximum = apertureMaximum;
            this.focalLengthBits = focalLengthBits;
            this.detector = detector;
            this.ibis = ibis;
            this.lensCommand = lensCommand;
            this.acceleration = acceleration;
            this.lensName = lensName;
            this.gyroAttitude = gyroAttitude;
        }

        public int getFlags() { return flags; }
        public long getPublicationGeneration() { return publicationGeneration; }
        public long getConfigGeneration() { return configGeneration; }
        public long getCatalogGeneration() { return catalogGeneration; }
        public long getWorkerGeneration() { return workerGeneration; }
        public int[] getActualNative() { return actualNative.clone(); }
        public int getActualShutterIndex() { return actual.shutter; }
        public int getActualApertureIndex() { return actual.aperture; }
        public int getActualIsoIndex() { return actual.iso; }
        public int getRecipeShutterIndex() { return recipe.shutter; }
        public int getRecipeApertureIndex() { return recipe.aperture; }
        public int getRecipeIsoIndex() { return recipe.iso; }
        public int getApertureMinimumIndex() { return apertureMinimum; }
        public int getApertureMaximumIndex() { return apertureMaximum; }
        public int getFocalLengthBits() { return focalLengthBits; }
        public boolean hasFocalLength() { return focalLengthBits != 0; }
        public float getFocalLengthMm() { return Float.intBitsToFloat(focalLengthBits); }
        public StarTelemetry.DetectorConfig getDetector() { return detector; }
        public StarTelemetry.IbisSample getIbis() { return ibis; }
        public LensCommandSnapshot getLensCommand() { return lensCommand; }
        public Acceleration getAcceleration() { return acceleration; }
        public String getLensName() { return lensName; }
        /** Null for a legacy STATE; a v7 record can separately report invalid samples. */
        public GyroAttitudeProtocol.Snapshot getGyroAttitude() { return gyroAttitude; }
        public boolean hasCatalogAvailable() { return (flags & FLAG_CATALOG_AVAILABLE) != 0; }
        public boolean hasActualAvailable() { return (flags & FLAG_ACTUAL_AVAILABLE) != 0; }
        public boolean hasRecipeAvailable() { return (flags & FLAG_RECIPE_AVAILABLE) != 0; }
        public boolean hasDetectorAvailable() { return (flags & FLAG_DETECTOR_AVAILABLE) != 0; }
        public boolean hasIbisAvailable() { return (flags & FLAG_IBIS_AVAILABLE) != 0; }
        public boolean hasAccelerationAvailable() {
            return (flags & FLAG_ACCELERATION_AVAILABLE) != 0;
        }
        public boolean isRecoveryTarget() { return (flags & FLAG_RECOVERY_TARGET) != 0; }
        public boolean hasCompleteMutableState() {
            return hasCatalogAvailable() && hasActualAvailable()
                    && hasRecipeAvailable() && hasDetectorAvailable()
                    && configGeneration != 0L && catalogGeneration != 0L;
        }

    }

    public static final class ResolvedState implements CameraExposureState {
        private final State state;
        private final ExposureCatalogs.Selection actual;
        private final String apertureMinimum;
        private final String apertureMaximum;

        private ResolvedState(State state, ExposureCatalogs catalogs) {
            this.state = state;
            actual = selectionAt(catalogs, state.actual);
            try {
                List<ExposureCatalogs.Option> apertures =
                        catalogs.get(ExposureCatalogs.Kind.APERTURE);
                apertureMinimum = apertures.get(state.apertureMinimum).getLabel();
                apertureMaximum = apertures.get(state.apertureMaximum).getLabel();
            } catch (IndexOutOfBoundsException error) {
                throw new IllegalArgumentException(
                        "STATE aperture bounds are outside the coherent catalog", error);
            }
        }

        public State getState() { return state; }
        @Override public int getShutterValue() { return state.actualNative[0]; }
        @Override public int getApertureValue() { return state.actualNative[1]; }
        @Override public int getIsoValue() { return state.actualNative[2]; }
        @Override public boolean hasFocalLength() { return state.hasFocalLength(); }
        @Override public int getFocalLengthBits() { return state.focalLengthBits; }
        @Override public float getFocalLengthMm() { return state.getFocalLengthMm(); }
        @Override public String getShutter() { return actual.getShutter(); }
        @Override public String getAperture() { return actual.getAperture(); }
        @Override public String getIso() { return actual.getIso(); }
        @Override public String apertureMinLabel() { return apertureMinimum; }
        @Override public String apertureMaxLabel() { return apertureMaximum; }
        @Override public boolean hasLensName() { return !state.lensName.isEmpty(); }
        @Override public String getLensName() { return state.lensName; }
    }

    /**
     * Lossless best-effort Linux observation of the stock cache's commanded/clamped MOVESTEP pair.
     *
     * <p>The protocol lanes are the controller's commanded yaw and pitch order. The synthetic
     * generation describes Linux observations only; it is not a stock-event counter or a
     * freshness proof. Physical/image polarity and optical degree calibration are not part of the
     * current wire contract.</p>
     */
    public static final class LensCommandSnapshot {
        private final boolean sourceMapped;
        private final boolean snapshotValid;
        private final long generation;
        private final int commandedYawMovestepRaw;
        private final int commandedPitchMovestepRaw;

        private LensCommandSnapshot(
                boolean sourceMapped,
                boolean snapshotValid,
                long generation,
                int commandedYawMovestepRaw,
                int commandedPitchMovestepRaw) {
            if (snapshotValid && !sourceMapped) {
                throw new IllegalArgumentException(
                        "lens-command valid flag requires a mapped source");
            }
            if (snapshotValid) {
                if (generation == 0L || generation > 0xffff_ffffL
                        || (generation & 1L) != 0L) {
                    throw new IllegalArgumentException(
                            "valid lens-command generation must be nonzero and even");
                }
            } else if (generation != 0L
                    || commandedYawMovestepRaw != 0
                    || commandedPitchMovestepRaw != 0) {
                throw new IllegalArgumentException(
                        "invalid lens-command snapshot must use canonical zero fields");
            }
            this.sourceMapped = sourceMapped;
            this.snapshotValid = snapshotValid;
            this.generation = generation;
            this.commandedYawMovestepRaw = commandedYawMovestepRaw;
            this.commandedPitchMovestepRaw = commandedPitchMovestepRaw;
        }

        public boolean isSourceMapped() { return sourceMapped; }
        public boolean isSnapshotValid() { return snapshotValid; }
        public long getGeneration() { return generation; }
        public int getCommandedYawMovestepRaw() { return commandedYawMovestepRaw; }
        public int getCommandedPitchMovestepRaw() { return commandedPitchMovestepRaw; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof LensCommandSnapshot)) return false;
            LensCommandSnapshot that = (LensCommandSnapshot) other;
            return sourceMapped == that.sourceMapped
                    && snapshotValid == that.snapshotValid
                    && generation == that.generation
                    && commandedYawMovestepRaw == that.commandedYawMovestepRaw
                    && commandedPitchMovestepRaw == that.commandedPitchMovestepRaw;
        }

        @Override public int hashCode() {
            return Objects.hash(
                    sourceMapped,
                    snapshotValid,
                    generation,
                    commandedYawMovestepRaw,
                    commandedPitchMovestepRaw);
        }
    }

    public static final class Acceleration {
        private final int flags;
        private final long mainGeneration;
        private final int xMillig;
        private final int yMillig;
        private final int zMillig;

        public Acceleration(
                int flags, long mainGeneration, int xMillig, int yMillig, int zMillig) {
            if ((flags & ~ACCELERATION_KNOWN_FLAGS) != 0) {
                throw new IllegalArgumentException("STAR acceleration has unknown flags");
            }
            boolean sourceMapped = (flags & ACCELERATION_FLAG_SOURCE_MAPPED) != 0;
            boolean layoutQualified = (flags & ACCELERATION_FLAG_LAYOUT_QUALIFIED) != 0;
            boolean snapshotCoherent =
                    (flags & ACCELERATION_FLAG_SNAPSHOT_COHERENT) != 0;
            boolean sourceFresh = (flags & ACCELERATION_FLAG_SOURCE_FRESH) != 0;
            if (layoutQualified && !sourceMapped) {
                throw new IllegalArgumentException(
                        "STAR acceleration layout-qualified flag requires a mapped source");
            }
            if (snapshotCoherent && !layoutQualified) {
                throw new IllegalArgumentException(
                        "STAR acceleration coherent flag requires a qualified layout");
            }
            if (sourceFresh && !snapshotCoherent) {
                throw new IllegalArgumentException(
                        "STAR acceleration fresh flag requires a coherent snapshot");
            }
            if (snapshotCoherent && mainGeneration == 0L) {
                throw new IllegalArgumentException(
                        "coherent STAR acceleration requires a nonzero MAIN generation");
            }
            if (mainGeneration < 0L || mainGeneration > 0xffff_ffffL) {
                throw new IllegalArgumentException("STAR acceleration generation is not u32");
            }
            if (!signed16(xMillig) || !signed16(yMillig) || !signed16(zMillig)) {
                throw new IllegalArgumentException("STAR acceleration lane is not i16");
            }
            this.flags = flags;
            this.mainGeneration = mainGeneration;
            this.xMillig = xMillig;
            this.yMillig = yMillig;
            this.zMillig = zMillig;
        }

        public int getFlags() { return flags; }
        public long getMainGeneration() { return mainGeneration; }
        public int getXMillig() { return xMillig; }
        public int getYMillig() { return yMillig; }
        public int getZMillig() { return zMillig; }
        public boolean isSourceMapped() {
            return (flags & ACCELERATION_FLAG_SOURCE_MAPPED) != 0;
        }
        public boolean isValid() { return flags == ACCELERATION_VALID_FLAGS; }
    }

    private static final class IndexTuple {
        final int shutter;
        final int aperture;
        final int iso;

        IndexTuple(int shutter, int aperture, int iso, boolean allowUnavailable) {
            if (allowUnavailable) {
                requireIndexOrUnavailable(shutter, "shutter index");
                requireIndexOrUnavailable(aperture, "aperture index");
                requireIndexOrUnavailable(iso, "ISO index");
            } else {
                requireIndexUnchecked(shutter, "shutter index");
                requireIndexUnchecked(aperture, "aperture index");
                requireIndexUnchecked(iso, "ISO index");
            }
            this.shutter = shutter;
            this.aperture = aperture;
            this.iso = iso;
        }

        void requireAvailable(String field) throws ProtocolException {
            requireIndex(shutter, field + " shutter index");
            requireIndex(aperture, field + " aperture index");
            requireIndex(iso, field + " ISO index");
        }

        void requireUnavailable(String field) throws ProtocolException {
            if (shutter != UNAVAILABLE_INDEX || aperture != UNAVAILABLE_INDEX
                    || iso != UNAVAILABLE_INDEX) {
                throw new ProtocolException("unavailable " + field + " indices are not ff");
            }
        }

        void put(byte[] value, int offset) {
            value[offset] = (byte) shutter;
            value[offset + 1] = (byte) aperture;
            value[offset + 2] = (byte) iso;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IndexTuple)) return false;
            IndexTuple that = (IndexTuple) other;
            return shutter == that.shutter && aperture == that.aperture && iso == that.iso;
        }

        @Override public int hashCode() { return Objects.hash(shutter, aperture, iso); }
    }

    private static StarTelemetry.DetectorConfig detectorFromWords(int[] words) {
        return new StarTelemetry.DetectorConfig(
                words[0], words[1], words[2], words[3], words[4], words[5]);
    }

    private static void putDetector(
            byte[] value, int offset, StarTelemetry.DetectorConfig detector) {
        putU16(value, offset, detector.getSigmaQ8());
        putU16(value, offset + 2, detector.getMinArea());
        putU16(value, offset + 4, detector.getMaxArea());
        putU16(value, offset + 6, detector.getMaxElongationQ8());
        putU16(value, offset + 8, detector.getMaxSharpnessQ8());
        putU16(value, offset + 10, detector.getBorderMargin());
    }

    private static void validateFocal(int bits) throws ProtocolException {
        if (bits == 0) return;
        float value = Float.intBitsToFloat(bits);
        if (!Float.isFinite(value) || value < 0.1f || value > 2047.96875f) {
            throw new ProtocolException("STATE focal length is invalid");
        }
    }

    private static String printableAscii(
            byte[] value, int offset, int length, int minimum, String field)
            throws ProtocolException {
        for (int index = 0; index < length; index++) {
            int character = u8(value[offset + index]);
            if (character < minimum || character > 0x7e) {
                throw new ProtocolException(field + " is not printable ASCII");
            }
        }
        return new String(value, offset, length, StandardCharsets.US_ASCII);
    }

    private static int[] i16x3(byte[] value, int offset) {
        return new int[]{i16(value, offset), i16(value, offset + 2), i16(value, offset + 4)};
    }

    private static int[] u16x6(byte[] value, int offset) {
        int[] result = new int[6];
        for (int index = 0; index < result.length; index++) {
            result[index] = u16(value, offset + index * 2);
        }
        return result;
    }

    private static boolean allZero(int[] values) {
        for (int value : values) if (value != 0) return false;
        return true;
    }

    private static int u8(byte value) { return value & 0xff; }
    private static int u16(byte[] value, int offset) {
        return u8(value[offset]) | (u8(value[offset + 1]) << 8);
    }
    private static int i16(byte[] value, int offset) { return (short) u16(value, offset); }
    private static long u32(byte[] value, int offset) {
        return ((long) u8(value[offset]))
                | ((long) u8(value[offset + 1]) << 8)
                | ((long) u8(value[offset + 2]) << 16)
                | ((long) u8(value[offset + 3]) << 24);
    }

    private static void putU16(byte[] value, int offset, int field) {
        value[offset] = (byte) field;
        value[offset + 1] = (byte) (field >>> 8);
    }

    private static void putU32(byte[] value, int offset, long field) {
        if (field < 0L || field > 0xffff_ffffL) {
            throw new IllegalArgumentException("u32 field is out of range");
        }
        value[offset] = (byte) field;
        value[offset + 1] = (byte) (field >>> 8);
        value[offset + 2] = (byte) (field >>> 16);
        value[offset + 3] = (byte) (field >>> 24);
    }

    private static void requireIndex(int value, String field) throws ProtocolException {
        if (value < 0 || value >= MAX_CATALOG_OPTIONS) {
            throw new ProtocolException(field + " is outside 0..127");
        }
    }

    private static void requireIndexOrUnavailable(int value, String field) {
        if ((value < 0 || value >= MAX_CATALOG_OPTIONS) && value != UNAVAILABLE_INDEX) {
            throw new IllegalArgumentException(field + " is neither 0..127 nor ff");
        }
    }

    private static void requireIndexUnchecked(int value, String field) {
        if (value < 0 || value >= MAX_CATALOG_OPTIONS) {
            throw new IllegalArgumentException(field + " is outside 0..127");
        }
    }

    private static boolean signed16(int value) {
        return value >= Short.MIN_VALUE && value <= Short.MAX_VALUE;
    }

    public static final class ProtocolException extends Exception {
        public ProtocolException(String message) { super(message); }
        public ProtocolException(String message, Throwable cause) { super(message, cause); }
    }
}
