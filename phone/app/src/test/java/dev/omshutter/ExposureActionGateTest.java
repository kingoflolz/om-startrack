package dev.omshutter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.omshutter.protocol.ExposureCatalogs;
import dev.omshutter.protocol.ExposureDraft;
import dev.omshutter.protocol.StarCaptureProtocol;
import dev.omshutter.protocol.ExposureFixture;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ExposureActionGateTest {
    @Test
    public void stockShutterDoesNotDependOnUnavailableStarSettings() {
        assertTrue(ExposureActionGate.isStockShutterReady(false, false, true));
        assertTrue(ExposureActionGate.isStockShutterReady(true, true, true));
        assertFalse(ExposureActionGate.isStockShutterReady(true, false, true));
    }

    @Test
    public void stockShutterFailsClosedDuringCaptureRecovery() {
        assertFalse(ExposureActionGate.isStockShutterReady(false, false, false));
        assertFalse(ExposureActionGate.isStockShutterReady(true, true, false));
    }

    @Test
    public void shutterRequiresIdleMatchingDesiredAndAuthoritativeState() throws Exception {
        Fixture fixture = new Fixture();

        assertTrue(fixture.shutterReady(false, false, false, false, true));
        assertFalse(fixture.shutterReady(true, false, false, false, true));
        assertFalse(fixture.shutterReady(false, true, false, false, true));
        assertFalse(fixture.shutterReady(false, false, true, false, true));
        assertFalse(fixture.shutterReady(false, false, false, true, true));
        assertFalse(fixture.shutterReady(false, false, false, false, false));

        fixture.liveDraft.selectFromUser(ExposureCatalogs.Kind.ISO, fixture.iso3200);
        assertFalse(fixture.shutterReady(false, false, false, false, true));
    }

    @Test
    public void detectAlsoRequiresCommittedDesiredRecipe() throws Exception {
        Fixture fixture = new Fixture();

        assertTrue(fixture.detectReady(fixture.recipe, true));
        assertFalse(fixture.detectReady(null, true));
        assertFalse(fixture.detectReady(
                new ExposureCatalogs.Selection("1/4", "4.0", "3200"), true));
        assertFalse(fixture.detectReady(fixture.recipe, false));

        fixture.recipeDraft.selectFromUser(ExposureCatalogs.Kind.ISO, fixture.iso3200);
        assertFalse(fixture.detectReady(fixture.recipe, true));
    }

    @Test
    public void cameraOwnedRecipeMayDifferFromLiveExposureAndStillUnlockDetect()
            throws Exception {
        ExposureCatalogs catalogs = new ExposureCatalogs(
                Arrays.asList(
                        option(ExposureCatalogs.Kind.SHUTTER, "1/4"),
                        option(ExposureCatalogs.Kind.SHUTTER, "60\"")),
                Arrays.asList(option(ExposureCatalogs.Kind.APERTURE, "4.0")),
                Arrays.asList(
                        option(ExposureCatalogs.Kind.ISO, "Auto"),
                        option(ExposureCatalogs.Kind.ISO, "1600")));
        ExposureCatalogs.Selection live = new ExposureCatalogs.Selection(
                "1/4", "4.0", "1600");
        ExposureCatalogs.Selection recipe = new ExposureCatalogs.Selection(
                "60\"", "4.0", "Auto");
        ExposureDraft liveDraft = new ExposureDraft();
        ExposureDraft recipeDraft = new ExposureDraft();
        liveDraft.update(catalogs, live);

        assertFalse(ExposureActionGate.isDetectReady(
                liveDraft,
                recipeDraft,
                state("1/4", "4.0", "1600"),
                live,
                null,
                null,
                false,
                false,
                false,
                false,
                true,
                false));

        // A camera-owned temporary recipe is independent of the persistent live settings.
        // Synchronizing it is also the MainActivity precondition that enables its editors.
        recipeDraft.update(catalogs, recipe);
        assertTrue(recipeDraft.isSynchronized());
        assertTrue(ExposureActionGate.isDetectReady(
                liveDraft,
                recipeDraft,
                state("1/4", "4.0", "1600"),
                live,
                recipe,
                recipe,
                false,
                false,
                false,
                false,
                true,
                true));
    }

    private static final class Fixture {
        private final ExposureCatalogs.Option shutter = option(
                ExposureCatalogs.Kind.SHUTTER, "1/4");
        private final ExposureCatalogs.Option aperture = option(
                ExposureCatalogs.Kind.APERTURE, "4.0");
        private final ExposureCatalogs.Option iso1600 = option(
                ExposureCatalogs.Kind.ISO, "1600");
        private final ExposureCatalogs.Option iso3200 = option(
                ExposureCatalogs.Kind.ISO, "3200");
        private final ExposureCatalogs catalogs = new ExposureCatalogs(
                Arrays.asList(shutter),
                Arrays.asList(aperture),
                Arrays.asList(iso1600, iso3200));
        private final ExposureCatalogs.Selection live = new ExposureCatalogs.Selection(
                "1/4", "4.0", "1600");
        private final ExposureCatalogs.Selection recipe = new ExposureCatalogs.Selection(
                "1/4", "4.0", "1600");
        private final ExposureDraft liveDraft = new ExposureDraft();
        private final ExposureDraft recipeDraft = new ExposureDraft();
        private final ExposureFixture state;

        private Fixture() throws Exception {
            liveDraft.update(catalogs, live);
            recipeDraft.update(catalogs, recipe);
            state = state("1/4", "4.0", "1600");
        }

        private boolean shutterReady(
                boolean liveAwaiting,
                boolean recipeAwaiting,
                boolean livePending,
                boolean recipePending,
                boolean serviceReady) {
            return ExposureActionGate.isShutterReady(
                    liveDraft,
                    state,
                    live,
                    liveAwaiting,
                    recipeAwaiting,
                    livePending,
                    recipePending,
                    serviceReady);
        }

        private boolean detectReady(
                ExposureCatalogs.Selection committedRecipe, boolean serviceReady) {
            return ExposureActionGate.isDetectReady(
                    liveDraft,
                    recipeDraft,
                    state,
                    live,
                    committedRecipe,
                    recipe,
                    false,
                    false,
                    false,
                    false,
                    true,
                    serviceReady);
        }
    }

    private static ExposureCatalogs.Option option(
            ExposureCatalogs.Kind kind, String label) {
        return new ExposureCatalogs.Option(kind, label);
    }

    private static ExposureFixture state(
            String shutter, String aperture, String iso) throws Exception {
        return new ExposureFixture(shutter, aperture, iso, aperture, aperture);
    }

    private static void writeLabel(ByteArrayOutputStream output, String label) {
        byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
        output.write(bytes.length);
        output.write(bytes, 0, bytes.length);
    }

    private static void writeHalfword(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void writeWord(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }
}
