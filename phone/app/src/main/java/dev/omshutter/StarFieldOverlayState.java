package dev.omshutter;

import java.util.Arrays;

/** Pure state holder for STAR plot classification and repeated-publication handling. */
final class StarFieldOverlayState {
    private long[] peakData = new long[0];
    private StarMatchOverlay matches = StarMatchOverlay.empty();

    void replacePeakData(long[] nextPeakData) {
        long[] replacement = nextPeakData == null ? new long[0] : nextPeakData.clone();
        if (replacement.length == 0 || !Arrays.equals(peakData, replacement)) {
            matches = StarMatchOverlay.empty();
        }
        peakData = replacement;
    }

    void setMatches(int[] patternSourceIndices, int[] validationSourceIndices) {
        StarMatchOverlay requested = StarMatchOverlay.of(
                patternSourceIndices, validationSourceIndices);
        int[] displayedPatterns = new int[peakData.length / 2];
        int[] displayedValidations = new int[peakData.length / 2];
        int patternCount = 0;
        int validationCount = 0;
        for (int offset = 0; offset < peakData.length; offset += 2) {
            int sourceIndex = offset / 2;
            switch (requested.kindForSourceIndex(sourceIndex)) {
                case PATTERN:
                    displayedPatterns[patternCount++] = sourceIndex;
                    break;
                case VALIDATION:
                    displayedValidations[validationCount++] = sourceIndex;
                    break;
                default:
                    break;
            }
        }
        matches = StarMatchOverlay.of(
                Arrays.copyOf(displayedPatterns, patternCount),
                Arrays.copyOf(displayedValidations, validationCount));
    }

    void clearMatches() {
        matches = StarMatchOverlay.empty();
    }

    StarMatchOverlay.Kind kindForSourceIndex(int sourceIndex) {
        return matches.kindForSourceIndex(sourceIndex);
    }

    int getPatternCount() {
        return matches.getPatternCount();
    }

    int getValidationCount() {
        return matches.getValidationCount();
    }

    boolean hasMatches() {
        return !matches.isEmpty();
    }
}
