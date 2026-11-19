package dev.omshutter;

import java.util.Arrays;

/** Immutable display classification keyed by published STAR peak index. */
final class StarMatchOverlay {
    enum Kind {
        NONE,
        PATTERN,
        VALIDATION
    }

    private static final int SOURCE_COUNT = 256;
    private static final StarMatchOverlay EMPTY = new StarMatchOverlay(new byte[SOURCE_COUNT]);

    private final byte[] kinds;
    private final int patternCount;
    private final int validationCount;

    private StarMatchOverlay(byte[] kinds) {
        this.kinds = kinds;
        int patterns = 0;
        int validations = 0;
        for (byte kind : kinds) {
            if (kind == Kind.PATTERN.ordinal()) patterns++;
            else if (kind == Kind.VALIDATION.ordinal()) validations++;
        }
        patternCount = patterns;
        validationCount = validations;
    }

    static StarMatchOverlay empty() {
        return EMPTY;
    }

    static StarMatchOverlay of(int[] patternSourceIndices, int[] validationSourceIndices) {
        int[] patterns = patternSourceIndices == null ? new int[0] : patternSourceIndices;
        int[] validations = validationSourceIndices == null ? new int[0] : validationSourceIndices;
        if (patterns.length == 0 && validations.length == 0) return EMPTY;

        byte[] kinds = new byte[SOURCE_COUNT];
        for (int sourceIndex : validations) {
            checkSourceIndex(sourceIndex);
            kinds[sourceIndex] = (byte) Kind.VALIDATION.ordinal();
        }
        // Pattern membership wins defensively if malformed input contains an overlap.
        for (int sourceIndex : patterns) {
            checkSourceIndex(sourceIndex);
            kinds[sourceIndex] = (byte) Kind.PATTERN.ordinal();
        }
        return new StarMatchOverlay(kinds);
    }

    Kind kindForSourceIndex(int sourceIndex) {
        if (sourceIndex < 0 || sourceIndex >= SOURCE_COUNT) return Kind.NONE;
        switch (kinds[sourceIndex]) {
            case 1:
                return Kind.PATTERN;
            case 2:
                return Kind.VALIDATION;
            default:
                return Kind.NONE;
        }
    }

    int getPatternCount() {
        return patternCount;
    }

    int getValidationCount() {
        return validationCount;
    }

    boolean isEmpty() {
        return patternCount == 0 && validationCount == 0;
    }

    private static void checkSourceIndex(int sourceIndex) {
        if (sourceIndex < 0 || sourceIndex >= SOURCE_COUNT) {
            throw new IllegalArgumentException("STAR source index is outside 0..255");
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StarMatchOverlay
                && Arrays.equals(kinds, ((StarMatchOverlay) other).kinds);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(kinds);
    }
}
