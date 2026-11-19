package dev.omshutter.ble;

import dev.omshutter.ApertureCatalogView;
import dev.omshutter.protocol.CameraExposureState;
import dev.omshutter.protocol.ExposureCatalogs;

/** Chooses the smallest safe refresh after one authoritative STATE update. */
final class ApertureStatePolicy {
    enum Refresh {
        NONE(0, 0),
        RECIPE_ONLY(0, 1),
        FULL_CATALOGS(3, 1);

        private final int catalogReads;
        private final int recipeReads;

        Refresh(int catalogReads, int recipeReads) {
            this.catalogReads = catalogReads;
            this.recipeReads = recipeReads;
        }

        int catalogReads() {
            return catalogReads;
        }

        int recipeReads() {
            return recipeReads;
        }
    }

    private ApertureStatePolicy() {}

    static Refresh classify(
            ExposureCatalogs fullCatalogs,
            CameraExposureState previous,
            CameraExposureState next) {
        if (fullCatalogs == null || next == null) return Refresh.FULL_CATALOGS;
        ExposureCatalogs filtered = ApertureCatalogView.filter(
                fullCatalogs,
                next.apertureMinLabel(),
                next.apertureMaxLabel(),
                next.getAperture());
        if (filtered == null
                || !filtered.contains(ExposureCatalogs.Selection.from(next))) {
            return Refresh.FULL_CATALOGS;
        }
        if (previous != null
                && (!previous.apertureMinLabel().equals(next.apertureMinLabel())
                    || !previous.apertureMaxLabel().equals(next.apertureMaxLabel()))) {
            return Refresh.RECIPE_ONLY;
        }
        return Refresh.NONE;
    }
}
