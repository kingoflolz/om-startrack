package dev.omshutter.ble;

import java.util.Arrays;

/**
 * Connection-scoped gate for reopening a bounded STAR catalog-initialization window.
 *
 * <p>Once one STATE-A/catalogs/STATE-B window is exhausted, identical STATE notifications must
 * not create an unlimited series of fresh windows. A changed catalog generation, config
 * generation, exact mutable-authority vector, or transition from incomplete to complete mutable
 * state is new evidence and may reserve exactly one probe. Worker/publication telemetry alone is
 * not evidence. The reservation is recorded immediately, so repeated notifications cannot race
 * the probe's eventual success or failure.
 */
final class StarInitializationRecoveryGate {
    enum ProbeDecision {
        ALLOW_PROBE,
        BLOCK_REPEATED,
        STALE_EPOCH
    }

    /** Parsed STATE evidence relevant to recovery; telemetry-only fields are intentionally absent. */
    static final class Signature {
        private static final long MAX_U32 = 0xffff_ffffL;

        private final boolean catalogAvailable;
        private final long catalogGeneration;
        private final boolean completeMutable;
        private final long configGeneration;
        // Diagnostic only. A broken worker may restart every 500 ms, so this is deliberately
        // excluded from recovery-evidence equality and admission.
        private final long workerGeneration;
        private final int[] mutableAuthority;

        private Signature(
                boolean catalogAvailable,
                long catalogGeneration,
                boolean completeMutable,
                long configGeneration,
                long workerGeneration,
                int[] mutableAuthority) {
            if (catalogGeneration < 0L || catalogGeneration > MAX_U32) {
                throw new IllegalArgumentException("catalog generation is outside u32");
            }
            if (configGeneration < 0L || configGeneration > MAX_U32
                    || workerGeneration < 0L || workerGeneration > MAX_U32) {
                throw new IllegalArgumentException(
                        "config or worker generation is outside u32");
            }
            if (catalogAvailable != (catalogGeneration != 0L)) {
                throw new IllegalArgumentException(
                        "catalog availability and generation disagree");
            }
            if (!catalogAvailable && completeMutable) {
                throw new IllegalArgumentException(
                        "unavailable catalog state cannot have complete mutable authority");
            }
            if (completeMutable != (configGeneration != 0L)) {
                throw new IllegalArgumentException(
                        "mutable completeness and config generation disagree");
            }
            this.catalogAvailable = catalogAvailable;
            this.catalogGeneration = catalogGeneration;
            this.completeMutable = completeMutable;
            this.configGeneration = configGeneration;
            this.workerGeneration = workerGeneration;
            this.mutableAuthority = mutableAuthority == null
                    ? new int[0] : mutableAuthority.clone();
        }

        static Signature of(
                boolean catalogAvailable,
                long catalogGeneration,
                boolean completeMutable) {
            return new Signature(
                    catalogAvailable,
                    catalogGeneration,
                    completeMutable,
                    completeMutable ? 1L : 0L,
                    0L,
                    new int[0]);
        }

        static Signature of(
                boolean catalogAvailable,
                long catalogGeneration,
                boolean completeMutable,
                long configGeneration,
                long workerGeneration,
                int... mutableAuthority) {
            return new Signature(
                    catalogAvailable,
                    catalogGeneration,
                    completeMutable,
                    configGeneration,
                    workerGeneration,
                    mutableAuthority);
        }

        static Signature unavailable() {
            return new Signature(false, 0L, false, 0L, 0L, new int[0]);
        }

        boolean isCatalogAvailable() { return catalogAvailable; }
        long getCatalogGeneration() { return catalogGeneration; }
        boolean isCompleteMutable() { return completeMutable; }
        long getConfigGeneration() { return configGeneration; }
        long getWorkerGeneration() { return workerGeneration; }
        int[] getMutableAuthority() { return mutableAuthority.clone(); }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Signature)) return false;
            Signature that = (Signature) other;
            return catalogAvailable == that.catalogAvailable
                    && catalogGeneration == that.catalogGeneration
                    && completeMutable == that.completeMutable
                    && configGeneration == that.configGeneration
                    && Arrays.equals(mutableAuthority, that.mutableAuthority);
        }

        @Override
        public int hashCode() {
            int result = catalogAvailable ? 1 : 0;
            result = 31 * result + Long.hashCode(catalogGeneration);
            result = 31 * result + (completeMutable ? 1 : 0);
            result = 31 * result + Long.hashCode(configGeneration);
            result = 31 * result + Arrays.hashCode(mutableAuthority);
            return result;
        }
    }

    private static final int NO_EPOCH = Integer.MIN_VALUE;

    private int connectionEpoch = NO_EPOCH;
    private Signature attemptStateA;
    private Signature attemptStateB;
    private boolean exhausted;
    private Signature exhaustedSignature;

    /** Clears all prior evidence when a new BLE connection epoch takes ownership. */
    void resetForConnection(int epoch) {
        if (connectionEpoch == epoch) return;
        connectionEpoch = epoch;
        clearAttempt();
        clearExhaustion();
    }

    /** Starts collecting the STATE endpoints for one bounded read-only fence attempt. */
    boolean beginFence(int epoch) {
        if (epoch != connectionEpoch) return false;
        clearAttempt();
        return true;
    }

    boolean recordStateA(int epoch, Signature signature) {
        if (epoch != connectionEpoch || signature == null) return false;
        attemptStateA = signature;
        attemptStateB = null;
        return true;
    }

    /** Records that STATE A could not be parsed or was not observed. */
    boolean recordUnknownStateA(int epoch) {
        if (epoch != connectionEpoch) return false;
        attemptStateA = null;
        attemptStateB = null;
        return true;
    }

    boolean recordStateB(int epoch, Signature signature) {
        if (epoch != connectionEpoch || signature == null) return false;
        attemptStateB = signature;
        return true;
    }

    /**
     * Seals the newest known endpoint as the exhausted signature. Unknown attempts retain an
     * already-reserved valid probe so another malformed STATE A cannot reopen the same window.
     */
    boolean markExhausted(int epoch) {
        if (epoch != connectionEpoch) return false;
        Signature observed = attemptStateB != null ? attemptStateB : attemptStateA;
        if (!exhausted) {
            exhausted = true;
            exhaustedSignature = observed;
        } else if (observed != null
                && (exhaustedSignature == null
                    || isNewRecoveryEvidence(exhaustedSignature, observed))) {
            exhaustedSignature = observed;
        }
        clearAttempt();
        return true;
    }

    /**
     * Classifies one valid parsed STATE notification and reserves new evidence immediately.
     */
    ProbeDecision classifyNotification(int epoch, Signature observed) {
        if (epoch != connectionEpoch) return ProbeDecision.STALE_EPOCH;
        if (observed == null) throw new IllegalArgumentException("STATE signature is null");
        if (!exhausted) return ProbeDecision.ALLOW_PROBE;
        if (exhaustedSignature != null
                && !isNewRecoveryEvidence(exhaustedSignature, observed)) {
            return ProbeDecision.BLOCK_REPEATED;
        }
        exhaustedSignature = observed;
        return ProbeDecision.ALLOW_PROBE;
    }

    /** A coherent available bank ends recovery suppression for this connection. */
    void clearAfterSuccess(int epoch) {
        if (epoch != connectionEpoch) return;
        clearAttempt();
        clearExhaustion();
    }

    /** Only a complete STATE-A/catalogs/STATE-B unavailable outcome resolves suppression. */
    void observeUnavailable(int epoch, boolean fencedStableUnavailable) {
        if (fencedStableUnavailable) clearAfterSuccess(epoch);
    }

    boolean isExhausted(int epoch) {
        return epoch == connectionEpoch && exhausted;
    }

    Signature getExhaustedSignature(int epoch) {
        return epoch == connectionEpoch && exhausted ? exhaustedSignature : null;
    }

    private static boolean isNewRecoveryEvidence(Signature previous, Signature observed) {
        if (previous.equals(observed)) return false;
        if (!previous.catalogAvailable) {
            return observed.catalogAvailable;
        }
        if (!observed.catalogAvailable) {
            return false;
        }
        if (previous.catalogGeneration != observed.catalogGeneration) {
            return true;
        }
        if (previous.completeMutable && !observed.completeMutable) return false;
        if (!previous.completeMutable && observed.completeMutable) return true;
        return previous.configGeneration != observed.configGeneration
                || !Arrays.equals(previous.mutableAuthority, observed.mutableAuthority);
    }

    private void clearAttempt() {
        attemptStateA = null;
        attemptStateB = null;
    }

    private void clearExhaustion() {
        exhausted = false;
        exhaustedSignature = null;
    }
}
