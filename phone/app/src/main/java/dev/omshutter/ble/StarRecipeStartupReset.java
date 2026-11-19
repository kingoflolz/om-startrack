package dev.omshutter.ble;

import dev.omshutter.protocol.ExposureCatalogs;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Resolves and fences the one automatic STAR recipe reset for a connection. */
final class StarRecipeStartupReset {
    private static final String PREFERRED_SHUTTER_LABEL = "1\"";
    private static final String PREFERRED_APERTURE_LABEL = "F4.0";
    private static final String PREFERRED_ISO_LABEL = "800";

    enum State {
        PENDING,
        WRITING,
        READY,
        FAILED
    }

    static final class Decision {
        private final ExposureCatalogs.Selection selection;
        private final boolean writeRequired;
        private final String failure;

        private Decision(
                ExposureCatalogs.Selection selection,
                boolean writeRequired,
                String failure) {
            this.selection = selection;
            this.writeRequired = writeRequired;
            this.failure = failure == null ? "" : failure;
        }

        static Decision failed(String failure) {
            return new Decision(null, false, failure);
        }

        static Decision ready(ExposureCatalogs.Selection selection) {
            return new Decision(selection, false, "");
        }

        static Decision write(ExposureCatalogs.Selection selection) {
            return new Decision(selection, true, "");
        }

        ExposureCatalogs.Selection selection() {
            return selection;
        }

        boolean writeRequired() {
            return writeRequired;
        }

        boolean failed() {
            return !failure.isEmpty();
        }

        String failure() {
            return failure;
        }
    }

    private State state = State.PENDING;
    private String failure = "";
    private boolean writeAllowed = true;
    private ExposureCatalogs.Selection expectedRecipe;

    void beginConnection() {
        beginConnection(true);
    }

    void beginConnection(boolean allowWrite) {
        state = State.PENDING;
        failure = "";
        writeAllowed = allowWrite;
        expectedRecipe = null;
    }

    Decision observeInitialRecipe(
            ExposureCatalogs catalogs,
            ExposureCatalogs.Selection existingRecipe) {
        if (state != State.PENDING) {
            throw new IllegalStateException("startup STAR recipe was already resolved");
        }
        if (catalogs == null || existingRecipe == null || !catalogs.contains(existingRecipe)) {
            return fail("Camera did not provide a coherent STAR recipe baseline");
        }

        ExposureCatalogs.Option shutter = resolveExactOneSecond(catalogs);
        if (shutter == null) {
            return fail("Camera has no unambiguous 1-second STAR shutter choice");
        }
        ExposureCatalogs.Option iso = resolveExactIso800(catalogs);
        if (iso == null) {
            return fail("Camera has no unambiguous ISO 800 STAR choice");
        }
        ExposureCatalogs.Option aperture = resolveAperture(catalogs);
        if (aperture == null) {
            return fail("Camera has no legal aperture for the STAR recipe");
        }

        ExposureCatalogs.Selection defaults = catalogs.toSelection(shutter, aperture, iso);
        expectedRecipe = defaults;
        if (defaults.equals(existingRecipe)) {
            state = State.READY;
            return Decision.ready(defaults);
        }
        if (!writeAllowed) {
            return fail(
                    "Previous startup STAR recipe delivery was ambiguous; the automatic reset was not retried");
        }
        state = State.WRITING;
        return Decision.write(defaults);
    }

    void writeSucceeded() {
        if (state != State.WRITING) {
            throw new IllegalStateException("no startup STAR recipe write is active");
        }
        state = State.READY;
        failure = "";
    }

    void userWriteSucceeded(ExposureCatalogs.Selection selection) {
        observeAuthoritativeRecipe(selection);
    }

    void observeAuthoritativeRecipe(ExposureCatalogs.Selection selection) {
        if (state != State.READY || selection == null) {
            throw new IllegalStateException("startup STAR recipe is not ready");
        }
        expectedRecipe = selection;
    }

    boolean acceptsObservedRecipe(ExposureCatalogs.Selection selection) {
        return state == State.READY
                && expectedRecipe != null
                && expectedRecipe.equals(selection);
    }

    void writeFailed(String reason) {
        if (state != State.WRITING) return;
        state = State.FAILED;
        failure = reason == null || reason.isEmpty()
                ? "Could not reset the STAR recipe to its defaults"
                : reason;
        expectedRecipe = null;
    }

    State state() {
        return state;
    }

    boolean isReady() {
        return state == State.READY;
    }

    boolean isWriting() {
        return state == State.WRITING;
    }

    String failure() {
        return failure;
    }

    private Decision fail(String reason) {
        state = State.FAILED;
        failure = reason;
        expectedRecipe = null;
        return Decision.failed(reason);
    }

    private static ExposureCatalogs.Option resolveExactOneSecond(
            ExposureCatalogs catalogs) {
        ExposureCatalogs.Option preferred = catalogs.findByLabel(
                ExposureCatalogs.Kind.SHUTTER, PREFERRED_SHUTTER_LABEL);
        if (preferred != null) return preferred;
        return uniqueSemanticMatch(
                catalogs.get(ExposureCatalogs.Kind.SHUTTER),
                StarRecipeStartupReset::isExactlyOneSecond);
    }

    private static ExposureCatalogs.Option resolveExactIso800(
            ExposureCatalogs catalogs) {
        ExposureCatalogs.Option preferred = catalogs.findByLabel(
                ExposureCatalogs.Kind.ISO, PREFERRED_ISO_LABEL);
        if (preferred != null) return preferred;
        return uniqueSemanticMatch(
                catalogs.get(ExposureCatalogs.Kind.ISO),
                StarRecipeStartupReset::isIso800);
    }

    private static ExposureCatalogs.Option resolveAperture(
            ExposureCatalogs catalogs) {
        ExposureCatalogs.Option preferred = catalogs.findByLabel(
                ExposureCatalogs.Kind.APERTURE, PREFERRED_APERTURE_LABEL);
        if (preferred != null) return preferred;
        ExposureCatalogs.Option semantic = uniqueSemanticMatch(
                catalogs.get(ExposureCatalogs.Kind.APERTURE),
                StarRecipeStartupReset::isF4);
        if (semantic != null) return semantic;
        List<ExposureCatalogs.Option> legal = catalogs.get(ExposureCatalogs.Kind.APERTURE);
        return legal.isEmpty() ? null : legal.get(0);
    }

    private interface LabelPredicate {
        boolean matches(String label);
    }

    private static ExposureCatalogs.Option uniqueSemanticMatch(
            List<ExposureCatalogs.Option> options,
            LabelPredicate predicate) {
        ArrayList<ExposureCatalogs.Option> matches = new ArrayList<>();
        for (ExposureCatalogs.Option option : options) {
            if (predicate.matches(option.getLabel())) matches.add(option);
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static boolean isExactlyOneSecond(String label) {
        if (label == null || label.isEmpty()) return false;
        if (label.endsWith("\"")) {
            BigDecimal seconds = parseUnsignedDecimal(
                    label.substring(0, label.length() - 1));
            return seconds != null && seconds.compareTo(BigDecimal.ONE) == 0;
        }
        int slash = label.indexOf('/');
        if (slash > 0 && slash == label.lastIndexOf('/') && slash < label.length() - 1) {
            BigDecimal numerator = parseUnsignedDecimal(label.substring(0, slash));
            BigDecimal denominator = parseUnsignedDecimal(label.substring(slash + 1));
            return numerator != null
                    && denominator != null
                    && denominator.signum() != 0
                    && numerator.compareTo(denominator) == 0;
        }
        // Bare camera shutter labels are reciprocal denominators. Only denominator 1 is 1 s.
        BigDecimal denominator = parseUnsignedDecimal(label);
        return denominator != null && denominator.compareTo(BigDecimal.ONE) == 0;
    }

    private static boolean isF4(String label) {
        if (label == null || label.isEmpty()) return false;
        String value = label;
        if (value.length() > 1 && (value.charAt(0) == 'F' || value.charAt(0) == 'f')) {
            value = value.substring(1);
        }
        BigDecimal aperture = parseUnsignedDecimal(value);
        return aperture != null && aperture.compareTo(BigDecimal.valueOf(4L)) == 0;
    }

    private static boolean isIso800(String label) {
        if (label == null || label.isEmpty()) return false;
        String value = label.toUpperCase(Locale.US);
        if (value.startsWith("ISO")) value = value.substring(3);
        BigDecimal iso = parseUnsignedDecimal(value);
        return iso != null && iso.compareTo(BigDecimal.valueOf(800L)) == 0;
    }

    private static BigDecimal parseUnsignedDecimal(String value) {
        if (value == null || value.isEmpty()) return null;
        boolean decimal = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '.') {
                if (decimal || index == 0 || index == value.length() - 1) return null;
                decimal = true;
            } else if (character < '0' || character > '9') {
                return null;
            }
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
