package dev.omshutter.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;

public class ExposureDraftTest {
    @Test
    public void pinnedRecipeSurvivesStateAndCatalogRefreshByExactLabel() {
        ExposureCatalogs first = catalogs("1/4", "1/8", "F4.0", "F4.5", "1600", "3200");
        ExposureDraft draft = new ExposureDraft();
        draft.update(first, selection("1/4", "F4.0", "1600"));
        draft.selectFromUser(
                ExposureCatalogs.Kind.APERTURE,
                first.findByLabel(ExposureCatalogs.Kind.APERTURE, "F4.5"));

        ExposureCatalogs refreshed = catalogs(
                "1/8", "1/4", "F4.5", "F4.0", "3200", "1600");
        draft.update(refreshed, selection("1/8", "F4.0", "3200"));

        assertTrue(draft.isSynchronized());
        assertTrue(draft.hasDirtyFields());
        assertEquals("1/4", draft.getSelected(ExposureCatalogs.Kind.SHUTTER).getLabel());
        assertEquals("F4.5", draft.getSelected(ExposureCatalogs.Kind.APERTURE).getLabel());
        assertEquals("1600", draft.getSelected(ExposureCatalogs.Kind.ISO).getLabel());
        assertEquals(selection("1/4", "F4.5", "1600"), draft.getSelection());
    }

    @Test
    public void authoritativeEchoClearsSatisfiedDirtyChoice() {
        ExposureCatalogs catalogs = catalogs();
        ExposureDraft draft = new ExposureDraft();
        draft.update(catalogs, selection("1/4", "F4.0", "1600"));
        draft.selectFromUser(
                ExposureCatalogs.Kind.ISO,
                catalogs.findByLabel(ExposureCatalogs.Kind.ISO, "3200"));
        assertTrue(draft.hasDirtyFields());

        draft.update(catalogs, selection("1/4", "F4.0", "3200"));

        assertFalse(draft.hasDirtyFields());
        assertEquals(selection("1/4", "F4.0", "3200"), draft.getSelection());
    }

    @Test
    public void removedDirtyLabelFallsBackAndRequiresOneReview() {
        ExposureCatalogs first = catalogs();
        ExposureDraft draft = new ExposureDraft();
        draft.update(first, selection("1/4", "F4.0", "1600"));
        draft.selectFromUser(
                ExposureCatalogs.Kind.ISO,
                first.findByLabel(ExposureCatalogs.Kind.ISO, "3200"));

        ExposureCatalogs changed = catalogs(
                "1/4", "1/8", "F4.0", "F4.5", "6400", "12800");
        draft.update(changed, selection("1/4", "F4.0", "6400"));

        assertTrue(draft.isSynchronized());
        assertFalse(draft.hasDirtyFields());
        assertEquals("6400", draft.getSelected(ExposureCatalogs.Kind.ISO).getLabel());
        assertTrue(draft.isSelectionReviewRequired());
        assertTrue(draft.consumeSelectionReviewRequired());
        assertFalse(draft.consumeSelectionReviewRequired());
    }

    @Test
    public void suspendPreservesPinnedRecipeAndInvalidateClearsIt() {
        ExposureCatalogs catalogs = catalogs();
        ExposureDraft draft = new ExposureDraft();
        draft.update(catalogs, selection("1/4", "F4.0", "1600"));
        draft.selectFromUser(
                ExposureCatalogs.Kind.SHUTTER,
                catalogs.findByLabel(ExposureCatalogs.Kind.SHUTTER, "1/8"));

        draft.suspend();
        assertFalse(draft.isSynchronized());
        expectIllegalState(draft::getSelection);

        ExposureCatalogs refreshed = catalogs();
        draft.update(refreshed, selection("1/4", "F4.5", "3200"));
        assertEquals(selection("1/8", "F4.0", "1600"), draft.getSelection());
        assertTrue(draft.hasDirtyFields());

        draft.invalidate();
        assertFalse(draft.isSynchronized());
        assertFalse(draft.hasDirtyFields());
    }

    @Test
    public void incoherentAuthoritativeSelectionFailsClosed() {
        ExposureDraft draft = new ExposureDraft();
        draft.update(catalogs(), selection("1/1000", "F4.0", "1600"));

        assertFalse(draft.isSynchronized());
        expectIllegalState(draft::getSelection);
    }

    private static ExposureCatalogs catalogs() {
        return catalogs("1/4", "1/8", "F4.0", "F4.5", "1600", "3200");
    }

    private static ExposureCatalogs catalogs(
            String shutter0,
            String shutter1,
            String aperture0,
            String aperture1,
            String iso0,
            String iso1) {
        return new ExposureCatalogs(
                Arrays.asList(
                        option(ExposureCatalogs.Kind.SHUTTER, shutter0),
                        option(ExposureCatalogs.Kind.SHUTTER, shutter1)),
                Arrays.asList(
                        option(ExposureCatalogs.Kind.APERTURE, aperture0),
                        option(ExposureCatalogs.Kind.APERTURE, aperture1)),
                Arrays.asList(
                        option(ExposureCatalogs.Kind.ISO, iso0),
                        option(ExposureCatalogs.Kind.ISO, iso1)));
    }

    private static ExposureCatalogs.Option option(
            ExposureCatalogs.Kind kind,
            String label) {
        return new ExposureCatalogs.Option(kind, label);
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
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }
}
