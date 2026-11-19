package dev.omshutter.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class StarProtocolTest {
    @Test
    public void mandatoryUuidsAndMtuMatchFrozenContract() {
        assertEquals(
                "7e6d7a50-2c56-4f17-9c51-9a5f64b02450",
                StarProtocol.SERVICE_UUID.toString());
        assertEquals(
                "7e6d7a50-2c56-4f17-9c51-9a5f64b02456",
                StarProtocol.LEASE_UUID.toString());
        assertEquals(160, StarProtocol.MINIMUM_ATT_MTU);
        assertEquals(125, StarProtocol.STATE_FIXED_BYTES);
        assertEquals(157, StarProtocol.STATE_MAX_BYTES);
        assertEquals(0xbf, StarProtocol.STATE_KNOWN_FLAGS);
        assertArrayEquals(new byte[]{45, 0}, StarProtocol.encodeLeaseSeconds(45));
    }

    @Test
    public void fixedStateParsesAndResolvesOnlyAgainstCatalogBank() throws Exception {
        StarProtocol.State state = StarProtocol.parseState(state(7, 9, 12));
        ExposureCatalogs catalogs = catalogs();
        StarProtocol.ResolvedState actual =
                StarProtocol.resolveActual(state, catalogs);

        assertEquals(7L, state.getConfigGeneration());
        assertEquals(9L, state.getCatalogGeneration());
        assertEquals("1s", actual.getShutter());
        assertEquals("F4", actual.getAperture());
        assertEquals("800", actual.getIso());
        assertEquals("2s", StarProtocol.resolveRecipe(state, catalogs).getShutter());
        assertEquals("F2.8", actual.apertureMinLabel());
        assertEquals("F5.6", actual.apertureMaxLabel());
        assertEquals("LNS", actual.getLensName());
    }

    @Test
    public void stateRejectsTrailingBytesAndNoncanonicalUnavailableCatalog() {
        byte[] valid = state(7, 9, 12);
        byte[] trailing = new byte[valid.length + 1];
        System.arraycopy(valid, 0, trailing, 0, valid.length);
        assertThrows(
                StarProtocol.ProtocolException.class,
                () -> StarProtocol.parseState(trailing));

        byte[] unavailable = state(7, 9, 12);
        unavailable[1] &= ~StarProtocol.FLAG_CATALOG_AVAILABLE;
        assertThrows(
                StarProtocol.ProtocolException.class,
                () -> StarProtocol.parseState(unavailable));
    }

    @Test
    public void stateRejectsReservedExposureFlag() {
        byte[] value = state(7, 9, 12);
        value[1] |= 0x40;
        assertThrows(
                StarProtocol.ProtocolException.class,
                () -> StarProtocol.parseState(value));
    }

    @Test
    public void configGenerationExistsExactlyWhenAllMutableBlocksAreComplete() {
        byte[] partialWithGeneration = state(7, 9, 12);
        partialWithGeneration[1] &= ~StarProtocol.FLAG_DETECTOR_AVAILABLE;
        for (int offset = 36; offset < 48; offset++) partialWithGeneration[offset] = 0;
        assertThrows(
                StarProtocol.ProtocolException.class,
                () -> StarProtocol.parseState(partialWithGeneration));

        byte[] completeWithoutGeneration = state(0, 9, 12);
        assertThrows(
                StarProtocol.ProtocolException.class,
                () -> StarProtocol.parseState(completeWithoutGeneration));
    }

    @Test
    public void q10AndLatestLensCommandHaveIndependentValidity() throws Exception {
        byte[] q10OnlyValue = state(7, 9, 12);
        q10OnlyValue[1] |= StarProtocol.FLAG_IBIS_AVAILABLE;
        q10OnlyValue[48] = (byte) (StarTelemetry.IBIS_FLAG_SOURCE_MAPPED
                | StarTelemetry.IBIS_FLAG_MIRROR_ENABLED
                | StarTelemetry.IBIS_FLAG_DOUBLE_READ_STABLE);
        putI16(q10OnlyValue, 49, 8);
        putI16(q10OnlyValue, 51, 100);
        putI16(q10OnlyValue, 53, -200);
        putI16(q10OnlyValue, 55, 300);
        StarProtocol.State q10Only = StarProtocol.parseState(q10OnlyValue);

        assertEquals(100, q10Only.getIbis().getRawX());
        assertFalse(q10Only.getLensCommand().isSourceMapped());
        assertFalse(q10Only.getLensCommand().isSnapshotValid());

        byte[] lensOnlyValue = state(7, 9, 13);
        lensOnlyValue[1] |= StarProtocol.FLAG_IBIS_AVAILABLE;
        lensOnlyValue[48] = (byte) (StarProtocol.IBIS_LENS_COMMAND_SOURCE_MAPPED
                | StarProtocol.IBIS_LENS_COMMAND_SNAPSHOT_VALID);
        putU32(lensOnlyValue, 57, 14);
        putI16(lensOnlyValue, 61, Short.MIN_VALUE);
        putI16(lensOnlyValue, 63, Short.MAX_VALUE);
        StarProtocol.State lensOnly = StarProtocol.parseState(lensOnlyValue);

        assertEquals(null, lensOnly.getIbis());
        assertTrue(lensOnly.getLensCommand().isSourceMapped());
        assertTrue(lensOnly.getLensCommand().isSnapshotValid());
        assertEquals(14L, lensOnly.getLensCommand().getGeneration());
        assertEquals(
                Short.MIN_VALUE,
                lensOnly.getLensCommand().getCommandedYawMovestepRaw());
        assertEquals(
                Short.MAX_VALUE,
                lensOnly.getLensCommand().getCommandedPitchMovestepRaw());

        byte[] mappedInvalidValue = state(7, 9, 14);
        mappedInvalidValue[1] |= StarProtocol.FLAG_IBIS_AVAILABLE;
        mappedInvalidValue[48] = (byte) StarProtocol.IBIS_LENS_COMMAND_SOURCE_MAPPED;
        StarProtocol.State mappedInvalid = StarProtocol.parseState(mappedInvalidValue);
        assertTrue(mappedInvalid.getLensCommand().isSourceMapped());
        assertFalse(mappedInvalid.getLensCommand().isSnapshotValid());
    }

    @Test
    public void latestLensCommandRejectsInvalidFlagsGenerationAndCanonicalFields() {
        byte[] validWithoutSource = state(7, 9, 12);
        validWithoutSource[1] |= StarProtocol.FLAG_IBIS_AVAILABLE;
        validWithoutSource[48] = (byte) StarProtocol.IBIS_LENS_COMMAND_SNAPSHOT_VALID;
        putU32(validWithoutSource, 57, 2);
        assertThrows(
                StarProtocol.ProtocolException.class,
                () -> StarProtocol.parseState(validWithoutSource));

        byte[] oddGeneration = state(7, 9, 12);
        oddGeneration[1] |= StarProtocol.FLAG_IBIS_AVAILABLE;
        oddGeneration[48] = (byte) (StarProtocol.IBIS_LENS_COMMAND_SOURCE_MAPPED
                | StarProtocol.IBIS_LENS_COMMAND_SNAPSHOT_VALID);
        putU32(oddGeneration, 57, 3);
        assertThrows(
                StarProtocol.ProtocolException.class,
                () -> StarProtocol.parseState(oddGeneration));

        byte[] invalidWithRawLane = state(7, 9, 12);
        invalidWithRawLane[1] |= StarProtocol.FLAG_IBIS_AVAILABLE;
        invalidWithRawLane[48] = (byte) StarProtocol.IBIS_LENS_COMMAND_SOURCE_MAPPED;
        putI16(invalidWithRawLane, 61, 1);
        assertThrows(
                StarProtocol.ProtocolException.class,
                () -> StarProtocol.parseState(invalidWithRawLane));

        byte[] outerAvailabilityMismatch = state(7, 9, 12);
        outerAvailabilityMismatch[48] =
                (byte) StarProtocol.IBIS_LENS_COMMAND_SOURCE_MAPPED;
        assertThrows(
                StarProtocol.ProtocolException.class,
                () -> StarProtocol.parseState(outerAvailabilityMismatch));
    }

    @Test
    public void catalogFenceRejectsGenerationChange() throws Exception {
        StarCatalogFence fence = availableCatalogFence(
                StarProtocol.parseState(state(7, 9, 12)));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fence.finish(StarProtocol.parseState(state(8, 10, 13))));
        assertEquals("catalog generation changed across STATE fence", error.getMessage());
    }

    @Test
    public void catalogFenceReturnsAvailableBankForOneStableGeneration() throws Exception {
        StarCatalogFence fence = availableCatalogFence(
                StarProtocol.parseState(state(7, 9, 12)));

        StarCatalogFence.Outcome outcome =
                fence.finish(StarProtocol.parseState(state(8, 9, 13)));

        assertEquals(StarCatalogFence.Availability.AVAILABLE, outcome.getAvailability());
        assertTrue(outcome.isAvailable());
        assertFalse(outcome.isStableUnavailable());
        assertEquals(9L, outcome.getBank().getGeneration());
        assertEquals("1s", outcome.getBank().getActual().getShutter());
    }

    @Test
    public void catalogFenceReturnsStableUnavailableForCanonicalColdBank() throws Exception {
        StarProtocol.State cold = StarProtocol.parseState(coldState(3, 4));
        StarCatalogFence fence = unavailableCatalogFence(cold);

        StarCatalogFence.Outcome outcome = fence.finish(cold);

        assertEquals(
                StarCatalogFence.Availability.STABLE_UNAVAILABLE,
                outcome.getAvailability());
        assertFalse(outcome.isAvailable());
        assertTrue(outcome.isStableUnavailable());
        IllegalStateException error = assertThrows(
                IllegalStateException.class, outcome::getBank);
        assertEquals("stable-unavailable catalog fence has no bank", error.getMessage());
    }

    @Test
    public void catalogFenceRejectsAvailabilityChangeAndMixedUnavailableValues()
            throws Exception {
        StarProtocol.State cold = StarProtocol.parseState(coldState(3, 4));
        StarCatalogFence availabilityChanged = unavailableCatalogFence(cold);
        IllegalArgumentException changed = assertThrows(
                IllegalArgumentException.class,
                () -> availabilityChanged.finish(
                        StarProtocol.parseState(state(7, 9, 5))));
        assertEquals("catalog availability changed across STATE fence", changed.getMessage());

        StarCatalogFence mixedValues = new StarCatalogFence();
        mixedValues.begin(cold);
        mixedValues.setCatalog(
                ExposureCatalogs.Kind.SHUTTER,
                StarProtocol.parseExposureCatalog(
                        ExposureCatalogs.Kind.SHUTTER, catalog("1s")));
        mixedValues.setCatalog(
                ExposureCatalogs.Kind.APERTURE,
                StarProtocol.parseExposureCatalog(
                        ExposureCatalogs.Kind.APERTURE, new byte[]{0}));
        mixedValues.setCatalog(
                ExposureCatalogs.Kind.ISO,
                StarProtocol.parseExposureCatalog(
                        ExposureCatalogs.Kind.ISO, new byte[]{0}));
        IllegalArgumentException mixed = assertThrows(
                IllegalArgumentException.class, () -> mixedValues.finish(cold));
        assertEquals(
                "catalog values disagree with stable-unavailable STATE fence",
                mixed.getMessage());
    }

    @Test
    public void catalogFenceRejectsEmptyAvailableBankAndIncompleteStateB() throws Exception {
        StarProtocol.State available = StarProtocol.parseState(state(7, 9, 12));
        StarCatalogFence emptyBank = unavailableCatalogFence(available);
        IllegalArgumentException empty = assertThrows(
                IllegalArgumentException.class, () -> emptyBank.finish(available));
        assertEquals(
                "available STATE fence contains an unavailable catalog",
                empty.getMessage());

        StarCatalogFence incompleteState = availableCatalogFence(available);
        IllegalArgumentException incomplete = assertThrows(
                IllegalArgumentException.class,
                () -> incompleteState.finish(
                        StarProtocol.parseState(catalogOnlyState(9, 13, 4))));
        assertEquals("STATE B mutable snapshot is incomplete", incomplete.getMessage());
    }

    @Test
    public void completeStateWriteIsExact27ByteCasAndBarrierIsPostAtt()
            throws Exception {
        ExposureCatalogs catalogs = catalogs();
        StarProtocol.State state = StarProtocol.parseState(state(7, 9, 12));
        StarProtocol.MutationTarget target = StarProtocol.target(
                state,
                catalogs,
                new ExposureCatalogs.Selection("2s", "F4", "1600"),
                new ExposureCatalogs.Selection("1s", "F5.6", "800"),
                StarTelemetry.DEFAULT_CONFIG);
        byte[] wire = StarProtocol.encodeStateWrite(target);

        assertEquals(27, wire.length);
        assertEquals(6, wire[0] & 0xff);
        assertEquals(7, wire[1] & 0xff);
        assertEquals(9, wire[5] & 0xff);
        assertArrayEquals(new byte[]{1, 1, 1}, slice(wire, 9, 3));
        assertArrayEquals(new byte[]{0, 2, 0}, slice(wire, 12, 3));

        StarStateMutationSession session = new StarStateMutationSession();
        session.begin(target);
        session.onState(matchingState(target));
        assertFalse(session.snapshot().isReady());
        session.onWriteComplete(true);
        assertFalse(session.snapshot().isReady());
        session.onState(matchingState(target));
        assertTrue(session.snapshot().isReady());
    }

    @Test
    public void unavailableCatalogIsExactlyCountZero() throws Exception {
        assertTrue(StarProtocol.parseExposureCatalog(
                ExposureCatalogs.Kind.ISO, new byte[]{0}).isEmpty());
        assertThrows(
                StarProtocol.ProtocolException.class,
                () -> StarProtocol.parseExposureCatalog(
                        ExposureCatalogs.Kind.ISO, new byte[]{0, 0}));
    }

    private static StarProtocol.State matchingState(
            StarProtocol.MutationTarget target) throws Exception {
        byte[] value = state(8, 9, 13);
        value[24] = (byte) target.getLiveShutterIndex();
        value[25] = (byte) target.getLiveApertureIndex();
        value[26] = (byte) target.getLiveIsoIndex();
        value[27] = (byte) target.getRecipeShutterIndex();
        value[28] = (byte) target.getRecipeApertureIndex();
        value[29] = (byte) target.getRecipeIsoIndex();
        putDetector(value, target.getDetector());
        return StarProtocol.parseState(value);
    }

    private static ExposureCatalogs catalogs() throws Exception {
        return new ExposureCatalogs(
                StarProtocol.parseExposureCatalog(
                        ExposureCatalogs.Kind.SHUTTER, catalog("1s", "2s")),
                StarProtocol.parseExposureCatalog(
                        ExposureCatalogs.Kind.APERTURE,
                        catalog("F2.8", "F4", "F5.6")),
                StarProtocol.parseExposureCatalog(
                        ExposureCatalogs.Kind.ISO, catalog("800", "1600")));
    }

    private static StarCatalogFence availableCatalogFence(
            StarProtocol.State stateA) throws Exception {
        StarCatalogFence fence = new StarCatalogFence();
        fence.begin(stateA);
        fence.setCatalog(
                ExposureCatalogs.Kind.SHUTTER,
                StarProtocol.parseExposureCatalog(
                        ExposureCatalogs.Kind.SHUTTER, catalog("1s", "2s")));
        fence.setCatalog(
                ExposureCatalogs.Kind.APERTURE,
                StarProtocol.parseExposureCatalog(
                        ExposureCatalogs.Kind.APERTURE,
                        catalog("F2.8", "F4", "F5.6")));
        fence.setCatalog(
                ExposureCatalogs.Kind.ISO,
                StarProtocol.parseExposureCatalog(
                        ExposureCatalogs.Kind.ISO, catalog("800", "1600")));
        return fence;
    }

    private static StarCatalogFence unavailableCatalogFence(
            StarProtocol.State stateA) throws Exception {
        StarCatalogFence fence = new StarCatalogFence();
        fence.begin(stateA);
        for (ExposureCatalogs.Kind kind : ExposureCatalogs.Kind.values()) {
            fence.setCatalog(
                    kind, StarProtocol.parseExposureCatalog(kind, new byte[]{0}));
        }
        return fence;
    }

    private static byte[] state(long config, long catalog, long publication) {
        byte[] value = new byte[128];
        value[0] = 7;
        value[80] = 1;
        value[1] = (byte) (StarProtocol.FLAG_CATALOG_AVAILABLE
                | StarProtocol.FLAG_ACTUAL_AVAILABLE
                | StarProtocol.FLAG_RECIPE_AVAILABLE
                | StarProtocol.FLAG_DETECTOR_AVAILABLE);
        putU32(value, 2, publication);
        putU32(value, 6, config);
        putU32(value, 10, catalog);
        putU32(value, 14, 3);
        putI16(value, 18, 11);
        putI16(value, 20, 22);
        putI16(value, 22, 33);
        value[24] = 0;
        value[25] = 1;
        value[26] = 0;
        value[27] = 1;
        value[28] = 1;
        value[29] = 1;
        value[30] = 0;
        value[31] = 2;
        putU32(value, 32, Float.floatToRawIntBits(300.0f) & 0xffff_ffffL);
        putDetector(value, StarTelemetry.DEFAULT_CONFIG);
        value[76] = 3;
        value[77] = 'L';
        value[78] = 'N';
        value[79] = 'S';
        return value;
    }

    private static byte[] coldState(long publication, long worker) {
        byte[] value = new byte[StarProtocol.STATE_FIXED_BYTES];
        value[0] = StarProtocol.STATE_VERSION;
        value[77] = 1;
        putU32(value, 2, publication);
        putU32(value, 14, worker);
        for (int offset = 24; offset <= 31; offset++) value[offset] = (byte) 0xff;
        return value;
    }

    private static byte[] catalogOnlyState(long catalog, long publication, long worker) {
        byte[] value = coldState(publication, worker);
        value[1] = StarProtocol.FLAG_CATALOG_AVAILABLE;
        putU32(value, 10, catalog);
        return value;
    }

    private static byte[] catalog(String... labels) {
        int size = 1;
        for (String label : labels) size += 1 + label.length();
        byte[] value = new byte[size];
        value[0] = (byte) labels.length;
        int offset = 1;
        for (String label : labels) {
            value[offset++] = (byte) label.length();
            for (int index = 0; index < label.length(); index++) {
                value[offset++] = (byte) label.charAt(index);
            }
        }
        return value;
    }

    private static byte[] slice(byte[] value, int offset, int length) {
        byte[] result = new byte[length];
        System.arraycopy(value, offset, result, 0, length);
        return result;
    }

    private static void putDetector(
            byte[] value, StarTelemetry.DetectorConfig detector) {
        putI16(value, 36, detector.getSigmaQ8());
        putI16(value, 38, detector.getMinArea());
        putI16(value, 40, detector.getMaxArea());
        putI16(value, 42, detector.getMaxElongationQ8());
        putI16(value, 44, detector.getMaxSharpnessQ8());
        putI16(value, 46, detector.getBorderMargin());
    }

    private static void putI16(byte[] value, int offset, int field) {
        value[offset] = (byte) field;
        value[offset + 1] = (byte) (field >>> 8);
    }

    private static void putU32(byte[] value, int offset, long field) {
        value[offset] = (byte) field;
        value[offset + 1] = (byte) (field >>> 8);
        value[offset + 2] = (byte) (field >>> 16);
        value[offset + 3] = (byte) (field >>> 24);
    }
}
