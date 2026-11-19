package dev.omshutter.protocol;

import java.util.EnumMap;

/** Per-field exposure draft whose dirty choices survive latest-value STATE refreshes. */
public final class ExposureDraft {
    private final EnumMap<ExposureCatalogs.Kind, ExposureCatalogs.Option> selected =
            new EnumMap<>(ExposureCatalogs.Kind.class);

    private ExposureCatalogs catalogs;
    private ExposureCatalogs.Selection authoritative;
    private boolean synchronizedState;
    private boolean selectionReviewRequired;

    public ExposureDraft() {}

    /**
     * Incorporates the latest coherent catalog and state selection. Once initialized, selected
     * labels remain pinned across STATE changes and rebind by exact label on catalog refresh.
     */
    public void update(
            ExposureCatalogs newCatalogs,
            ExposureCatalogs.Selection newAuthoritative) {
        if (newCatalogs == null || newAuthoritative == null
                || !newCatalogs.contains(newAuthoritative)) {
            suspend();
            return;
        }

        catalogs = newCatalogs;
        authoritative = newAuthoritative;
        synchronizedState = true;

        for (ExposureCatalogs.Kind kind : ExposureCatalogs.Kind.values()) {
            ExposureCatalogs.Option authoritativeOption =
                    newCatalogs.findByLabel(kind, newAuthoritative.get(kind));
            ExposureCatalogs.Option priorSelection = selected.get(kind);
            if (priorSelection == null) {
                selected.put(kind, authoritativeOption);
                continue;
            }

            ExposureCatalogs.Option rebound =
                    newCatalogs.findByLabel(kind, priorSelection.getLabel());
            if (rebound == null) {
                selected.put(kind, authoritativeOption);
                selectionReviewRequired = true;
            } else {
                selected.put(kind, rebound);
            }
        }
    }

    public void selectFromUser(
            ExposureCatalogs.Kind kind,
            ExposureCatalogs.Option option) {
        if (!isSynchronized() || kind == null || option == null
                || !option.equals(catalogs.findByLabel(kind, option.getLabel()))) {
            throw new IllegalArgumentException("selection is not in the active catalog");
        }
        selected.put(kind, option);
    }

    public ExposureCatalogs.Option getSelected(ExposureCatalogs.Kind kind) {
        if (kind == null) throw new IllegalArgumentException("exposure kind is null");
        return selected.get(kind);
    }

    public boolean isSynchronized() {
        if (!synchronizedState || catalogs == null || authoritative == null
                || !catalogs.contains(authoritative)) {
            return false;
        }
        for (ExposureCatalogs.Kind kind : ExposureCatalogs.Kind.values()) {
            ExposureCatalogs.Option option = selected.get(kind);
            if (option == null
                    || !option.equals(catalogs.findByLabel(kind, option.getLabel()))) {
                return false;
            }
        }
        return true;
    }

    public boolean isDirty(ExposureCatalogs.Kind kind) {
        if (kind == null) throw new IllegalArgumentException("exposure kind is null");
        ExposureCatalogs.Option option = selected.get(kind);
        return isSynchronized()
                && option != null
                && !option.getLabel().equals(authoritative.get(kind));
    }

    public boolean hasDirtyFields() {
        for (ExposureCatalogs.Kind kind : ExposureCatalogs.Kind.values()) {
            if (isDirty(kind)) return true;
        }
        return false;
    }

    public ExposureCatalogs.Selection getSelection() {
        if (!isSynchronized()) {
            throw new IllegalStateException("exposure draft is not synchronized");
        }
        return catalogs.toSelection(
                selected.get(ExposureCatalogs.Kind.SHUTTER),
                selected.get(ExposureCatalogs.Kind.APERTURE),
                selected.get(ExposureCatalogs.Kind.ISO));
    }

    public boolean isSelectionReviewRequired() {
        return selectionReviewRequired;
    }

    public boolean consumeSelectionReviewRequired() {
        boolean result = selectionReviewRequired;
        selectionReviewRequired = false;
        return result;
    }

    public void invalidate() {
        catalogs = null;
        authoritative = null;
        selected.clear();
        synchronizedState = false;
        selectionReviewRequired = false;
    }

    /** Temporarily disables editing while retaining label-backed dirty choices. */
    public void suspend() {
        authoritative = null;
        synchronizedState = false;
    }

}
