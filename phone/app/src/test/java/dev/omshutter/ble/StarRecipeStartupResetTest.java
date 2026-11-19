package dev.omshutter.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.omshutter.protocol.ExposureCatalogs;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class StarRecipeStartupResetTest {
    @Test
    public void replacesExistingRecipeWithCanonicalDefaults() {
        ExposureCatalogs catalogs = catalogs(
                labels("1/125", "1\"", "2\""),
                labels("F2.8", "F4.0", "F5.6"),
                labels("200", "800", "3200"));
        ExposureCatalogs.Selection existing = selection("2\"", "F5.6", "3200");
        StarRecipeStartupReset reset = new StarRecipeStartupReset();

        StarRecipeStartupReset.Decision decision =
                reset.observeInitialRecipe(catalogs, existing);

        assertFalse(decision.failed());
        assertTrue(decision.writeRequired());
        assertEquals(selection("1\"", "F4.0", "800"), decision.selection());
        assertEquals(StarRecipeStartupReset.State.WRITING, reset.state());
        assertFalse(reset.isReady());
    }

    @Test
    public void exactExistingDefaultsNeedNoWrite() {
        ExposureCatalogs catalogs = catalogs(
                labels("1/125", "1\""),
                labels("F4.0", "F5.6"),
                labels("800", "1600"));
        ExposureCatalogs.Selection defaults = selection("1\"", "F4.0", "800");
        StarRecipeStartupReset reset = new StarRecipeStartupReset();

        StarRecipeStartupReset.Decision decision =
                reset.observeInitialRecipe(catalogs, defaults);

        assertFalse(decision.writeRequired());
        assertEquals(defaults, decision.selection());
        assertTrue(reset.isReady());
    }

    @Test
    public void resolvesUnambiguousSemanticCameraLabels() {
        ExposureCatalogs catalogs = catalogs(
                labels("125", "1.0\""),
                labels("2.8", "4", "5.6"),
                labels("ISO200", "ISO800", "ISO1600"));
        StarRecipeStartupReset reset = new StarRecipeStartupReset();

        StarRecipeStartupReset.Decision decision = reset.observeInitialRecipe(
                catalogs, selection("125", "2.8", "ISO200"));

        assertTrue(decision.writeRequired());
        assertEquals(selection("1.0\"", "4", "ISO800"), decision.selection());
    }

    @Test
    public void usesWidestLegalApertureWhenF4IsUnavailable() {
        // The service passes the STATE-bounded aperture view, whose first option is widest.
        ExposureCatalogs catalogs = catalogs(
                labels("1\""),
                labels("F5.6", "F8.0", "F11"),
                labels("800"));
        StarRecipeStartupReset reset = new StarRecipeStartupReset();

        StarRecipeStartupReset.Decision decision = reset.observeInitialRecipe(
                catalogs, selection("1\"", "F8.0", "800"));

        assertTrue(decision.writeRequired());
        assertEquals(selection("1\"", "F5.6", "800"), decision.selection());
    }

    @Test
    public void missingOrAmbiguousRequiredChoiceFailsClosed() {
        StarRecipeStartupReset missingShutter = new StarRecipeStartupReset();
        StarRecipeStartupReset.Decision noShutter = missingShutter.observeInitialRecipe(
                catalogs(labels("1/2", "2\""), labels("F4.0"), labels("800")),
                selection("2\"", "F4.0", "800"));
        assertTrue(noShutter.failed());
        assertNull(noShutter.selection());
        assertTrue(noShutter.failure().contains("1-second"));
        assertEquals(StarRecipeStartupReset.State.FAILED, missingShutter.state());

        StarRecipeStartupReset ambiguousShutter = new StarRecipeStartupReset();
        StarRecipeStartupReset.Decision ambiguous = ambiguousShutter.observeInitialRecipe(
                catalogs(labels("1.0\"", "1/1"), labels("F4.0"), labels("800")),
                selection("1.0\"", "F4.0", "800"));
        assertTrue(ambiguous.failed());
        assertTrue(ambiguous.failure().contains("unambiguous"));

        StarRecipeStartupReset missingIso = new StarRecipeStartupReset();
        StarRecipeStartupReset.Decision noIso = missingIso.observeInitialRecipe(
                catalogs(labels("1\""), labels("F4.0"), labels("400", "1600")),
                selection("1\"", "F4.0", "400"));
        assertTrue(noIso.failed());
        assertTrue(noIso.failure().contains("ISO 800"));
    }

    @Test
    public void writeOutcomeIsStickyUntilNextConnection() {
        ExposureCatalogs catalogs = catalogs(
                labels("1\"", "2\""), labels("F4.0"), labels("800"));
        StarRecipeStartupReset reset = new StarRecipeStartupReset();
        reset.observeInitialRecipe(catalogs, selection("2\"", "F4.0", "800"));

        reset.writeFailed("write outcome was ambiguous");
        assertEquals(StarRecipeStartupReset.State.FAILED, reset.state());
        assertEquals("write outcome was ambiguous", reset.failure());
        assertFalse(reset.isReady());
        expectIllegalState(() -> reset.observeInitialRecipe(
                catalogs, selection("1\"", "F4.0", "800")));

        reset.beginConnection();
        StarRecipeStartupReset.Decision next = reset.observeInitialRecipe(
                catalogs, selection("2\"", "F4.0", "800"));
        assertTrue(next.writeRequired());
        reset.writeSucceeded();
        assertTrue(reset.isReady());
    }

    @Test
    public void reconnectCanVerifyButNeverReplayAnAmbiguousAutomaticWrite() {
        ExposureCatalogs catalogs = catalogs(
                labels("1\"", "2\""), labels("F4.0"), labels("800"));
        StarRecipeStartupReset reset = new StarRecipeStartupReset();

        reset.beginConnection(false);
        StarRecipeStartupReset.Decision mismatch = reset.observeInitialRecipe(
                catalogs, selection("2\"", "F4.0", "800"));
        assertTrue(mismatch.failed());
        assertTrue(mismatch.failure().contains("not retried"));

        reset.beginConnection(false);
        StarRecipeStartupReset.Decision applied = reset.observeInitialRecipe(
                catalogs, selection("1\"", "F4.0", "800"));
        assertFalse(applied.failed());
        assertFalse(applied.writeRequired());
        assertTrue(reset.isReady());
    }

    @Test
    public void laterReadsMustMatchTheStartupOrExplicitUserSelection() {
        ExposureCatalogs catalogs = catalogs(
                labels("1\"", "2\""), labels("F4.0"), labels("800", "1600"));
        ExposureCatalogs.Selection old = selection("2\"", "F4.0", "1600");
        ExposureCatalogs.Selection defaults = selection("1\"", "F4.0", "800");
        StarRecipeStartupReset reset = new StarRecipeStartupReset();

        reset.observeInitialRecipe(catalogs, old);
        reset.writeSucceeded();
        assertTrue(reset.acceptsObservedRecipe(defaults));
        assertFalse(reset.acceptsObservedRecipe(old));

        reset.userWriteSucceeded(old);
        assertTrue(reset.acceptsObservedRecipe(old));
        assertFalse(reset.acceptsObservedRecipe(defaults));

        reset.beginConnection();
        assertFalse(reset.acceptsObservedRecipe(old));
    }

    private static ExposureCatalogs catalogs(
            List<String> shutters,
            List<String> apertures,
            List<String> isos) {
        return new ExposureCatalogs(
                options(ExposureCatalogs.Kind.SHUTTER, shutters),
                options(ExposureCatalogs.Kind.APERTURE, apertures),
                options(ExposureCatalogs.Kind.ISO, isos));
    }

    private static List<String> labels(String... labels) {
        return Arrays.asList(labels);
    }

    private static List<ExposureCatalogs.Option> options(
            ExposureCatalogs.Kind kind,
            List<String> labels) {
        ArrayList<ExposureCatalogs.Option> result = new ArrayList<>();
        for (String label : labels) result.add(new ExposureCatalogs.Option(kind, label));
        return result;
    }

    private static ExposureCatalogs.Selection selection(
            String shutter,
            String aperture,
            String iso) {
        return new ExposureCatalogs.Selection(shutter, aperture, iso);
    }

    private static void expectIllegalState(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("expected IllegalStateException");
    }
}
