package dev.omshutter.protocol;

import java.util.List;

/** One-shot STATE-A/catalogs/STATE-B coherence fence for a STAR catalog bank. */
public final class StarCatalogFence {
    public enum Availability {
        AVAILABLE,
        STABLE_UNAVAILABLE
    }

    /** Result of one complete STATE-A/catalogs/STATE-B read fence. */
    public static final class Outcome {
        private final Availability availability;
        private final Bank bank;

        private Outcome(Availability availability, Bank bank) {
            this.availability = availability;
            this.bank = bank;
        }

        private static Outcome available(Bank bank) {
            return new Outcome(Availability.AVAILABLE, bank);
        }

        private static Outcome stableUnavailable() {
            return new Outcome(Availability.STABLE_UNAVAILABLE, null);
        }

        public Availability getAvailability() { return availability; }
        public boolean isAvailable() { return availability == Availability.AVAILABLE; }
        public boolean isStableUnavailable() {
            return availability == Availability.STABLE_UNAVAILABLE;
        }

        public Bank getBank() {
            if (bank == null) {
                throw new IllegalStateException("stable-unavailable catalog fence has no bank");
            }
            return bank;
        }
    }

    public static final class Bank {
        private final long generation;
        private final ExposureCatalogs catalogs;
        private final StarProtocol.State state;
        private final StarProtocol.ResolvedState actual;
        private final ExposureCatalogs.Selection recipe;

        private Bank(
                long generation,
                ExposureCatalogs catalogs,
                StarProtocol.State state,
                StarProtocol.ResolvedState actual,
                ExposureCatalogs.Selection recipe) {
            this.generation = generation;
            this.catalogs = catalogs;
            this.state = state;
            this.actual = actual;
            this.recipe = recipe;
        }

        public long getGeneration() { return generation; }
        public ExposureCatalogs getCatalogs() { return catalogs; }
        public StarProtocol.State getState() { return state; }
        public StarProtocol.ResolvedState getActual() { return actual; }
        public ExposureCatalogs.Selection getRecipe() { return recipe; }
    }

    private StarProtocol.State stateA;
    private List<ExposureCatalogs.Option> shutter;
    private List<ExposureCatalogs.Option> aperture;
    private List<ExposureCatalogs.Option> iso;

    public void begin(StarProtocol.State first) {
        if (first == null) throw new IllegalArgumentException("STATE A is null");
        stateA = first;
        shutter = null;
        aperture = null;
        iso = null;
    }

    public void setCatalog(ExposureCatalogs.Kind kind, List<ExposureCatalogs.Option> options) {
        if (stateA == null) throw new IllegalStateException("STATE A was not observed");
        if (kind == null || options == null) throw new IllegalArgumentException("catalog is null");
        if (kind == ExposureCatalogs.Kind.SHUTTER) shutter = options;
        else if (kind == ExposureCatalogs.Kind.APERTURE) aperture = options;
        else if (kind == ExposureCatalogs.Kind.ISO) iso = options;
        else throw new IllegalArgumentException("unknown catalog kind");
    }

    /**
     * Accepts only the exact contract fence. A canonically unavailable bank is a safe, explicit
     * outcome; a torn bank or indexed-state mismatch still fails atomically. Callers may begin a
     * fresh read-only fence without replaying a mutation.
     */
    public Outcome finish(StarProtocol.State stateB) {
        if (stateA == null || stateB == null
                || shutter == null || aperture == null || iso == null) {
            throw new IllegalStateException("catalog fence is incomplete");
        }

        boolean availableA = stateA.hasCatalogAvailable();
        boolean availableB = stateB.hasCatalogAvailable();
        long generationA = stateA.getCatalogGeneration();
        long generationB = stateB.getCatalogGeneration();
        boolean allCatalogsEmpty = shutter.isEmpty() && aperture.isEmpty() && iso.isEmpty();

        if (!availableA && !availableB && generationA == 0L && generationB == 0L) {
            if (!allCatalogsEmpty) {
                throw new IllegalArgumentException(
                        "catalog values disagree with stable-unavailable STATE fence");
            }
            return Outcome.stableUnavailable();
        }
        if (availableA != availableB) {
            throw new IllegalArgumentException(
                    "catalog availability changed across STATE fence");
        }
        if (!availableA || generationA == 0L || generationB == 0L) {
            throw new IllegalArgumentException(
                    "STATE fence catalog availability or generation is noncanonical");
        }
        if (generationB != generationA) {
            throw new IllegalArgumentException("catalog generation changed across STATE fence");
        }
        if (shutter.isEmpty() || aperture.isEmpty() || iso.isEmpty()) {
            throw new IllegalArgumentException(
                    "available STATE fence contains an unavailable catalog");
        }
        ExposureCatalogs catalogs = new ExposureCatalogs(shutter, aperture, iso);
        StarProtocol.ResolvedState actual = StarProtocol.resolveActual(stateB, catalogs);
        ExposureCatalogs.Selection recipe = StarProtocol.resolveRecipe(stateB, catalogs);
        if (actual == null || recipe == null || !stateB.hasDetectorAvailable()) {
            throw new IllegalArgumentException("STATE B mutable snapshot is incomplete");
        }
        return Outcome.available(
                new Bank(generationA, catalogs, stateB, actual, recipe));
    }

    public void clear() {
        stateA = null;
        shutter = null;
        aperture = null;
        iso = null;
    }
}
