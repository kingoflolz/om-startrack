package dev.omshutter.protocol;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Separates explicit Spinner choices from delayed callbacks caused by adapter updates. */
public final class ExposureSelectionGate {
    private final EnumMap<
            ExposureCatalogs.Kind,
            Map<ExposureCatalogs.Option, Integer>> expectedProgrammatic =
            new EnumMap<>(ExposureCatalogs.Kind.class);
    private final EnumMap<ExposureCatalogs.Kind, Long> programmaticUpdates =
            new EnumMap<>(ExposureCatalogs.Kind.class);
    private final EnumMap<ExposureCatalogs.Kind, Boolean> userArmed =
            new EnumMap<>(ExposureCatalogs.Kind.class);
    private long nextProgrammaticUpdate = 1;

    public ExposureSelectionGate() {
        clear();
    }

    public void expectProgrammatic(
            ExposureCatalogs.Kind kind,
            ExposureCatalogs.Option option) {
        requireKind(kind);
        userArmed.put(kind, false);
        if (option == null) {
            expectedProgrammatic.remove(kind);
        } else if (option.getKind() != kind) {
            throw new IllegalArgumentException("programmatic option kind does not match");
        } else {
            Map<ExposureCatalogs.Option, Integer> expected =
                    expectedProgrammatic.computeIfAbsent(
                            kind, ignored -> new LinkedHashMap<>());
            expected.put(option, expected.getOrDefault(option, 0) + 1);
        }
    }

    /**
     * Suppresses every callback for one adapter population epoch. The caller ends the returned
     * epoch from a UI-queue callback posted after adapter replacement and setSelection calls.
     */
    public long beginProgrammaticUpdate(ExposureCatalogs.Kind kind) {
        requireKind(kind);
        long epoch = nextProgrammaticUpdate++;
        programmaticUpdates.put(kind, epoch);
        expectedProgrammatic.remove(kind);
        userArmed.put(kind, false);
        return epoch;
    }

    /** Ends only the matching population epoch; an older delayed end cannot release a newer one. */
    public void endProgrammaticUpdate(ExposureCatalogs.Kind kind, long epoch) {
        requireKind(kind);
        Long active = programmaticUpdates.get(kind);
        if (active != null && active.longValue() == epoch) programmaticUpdates.remove(kind);
    }

    public void armUserSelection(ExposureCatalogs.Kind kind) {
        requireKind(kind);
        userArmed.put(kind, true);
    }

    public boolean shouldAccept(
            ExposureCatalogs.Kind kind,
            ExposureCatalogs.Option option) {
        requireKind(kind);
        if (option == null || option.getKind() != kind) return false;
        if (programmaticUpdates.containsKey(kind)) return false;

        Map<ExposureCatalogs.Option, Integer> expected = expectedProgrammatic.get(kind);
        Integer remaining = expected == null ? null : expected.get(option);
        if (remaining != null) {
            if (remaining > 1) {
                expected.put(option, remaining - 1);
            } else {
                expected.remove(option);
            }
            if (expected.isEmpty()) expectedProgrammatic.remove(kind);
            return false;
        }
        if (Boolean.TRUE.equals(userArmed.get(kind))) {
            userArmed.put(kind, false);
            expectedProgrammatic.remove(kind);
            return true;
        }
        return false;
    }

    public void clear() {
        expectedProgrammatic.clear();
        programmaticUpdates.clear();
        for (ExposureCatalogs.Kind kind : ExposureCatalogs.Kind.values()) {
            userArmed.put(kind, false);
        }
    }

    private static void requireKind(ExposureCatalogs.Kind kind) {
        if (kind == null) throw new IllegalArgumentException("exposure kind is null");
    }
}
