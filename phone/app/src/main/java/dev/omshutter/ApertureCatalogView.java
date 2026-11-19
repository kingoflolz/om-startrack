package dev.omshutter;

import dev.omshutter.protocol.ExposureCatalogs;

import java.util.List;

/** Builds the legal current-lens aperture slice without changing camera-authored labels. */
public final class ApertureCatalogView {
    private ApertureCatalogView() {}

    public static ExposureCatalogs filter(
            ExposureCatalogs fullCatalogs,
            String minimumLabel,
            String maximumLabel,
            String currentLabel) {
        if (fullCatalogs == null
                || minimumLabel == null
                || maximumLabel == null
                || currentLabel == null) {
            return null;
        }
        List<ExposureCatalogs.Option> aperture =
                fullCatalogs.get(ExposureCatalogs.Kind.APERTURE);
        int minimumIndex = indexOfLabel(aperture, minimumLabel);
        int maximumIndex = indexOfLabel(aperture, maximumLabel);
        int currentIndex = indexOfLabel(aperture, currentLabel);
        if (minimumIndex < 0
                || maximumIndex < minimumIndex
                || currentIndex < minimumIndex
                || currentIndex > maximumIndex) {
            return null;
        }
        if (minimumIndex == 0 && maximumIndex == aperture.size() - 1) {
            return fullCatalogs;
        }
        return new ExposureCatalogs(
                fullCatalogs.get(ExposureCatalogs.Kind.SHUTTER),
                aperture.subList(minimumIndex, maximumIndex + 1),
                fullCatalogs.get(ExposureCatalogs.Kind.ISO));
    }

    private static int indexOfLabel(
            List<ExposureCatalogs.Option> options, String label) {
        for (int index = 0; index < options.size(); index++) {
            if (options.get(index).getLabel().equals(label)) return index;
        }
        return -1;
    }
}
