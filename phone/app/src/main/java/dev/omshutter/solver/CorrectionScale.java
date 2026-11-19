package dev.omshutter.solver;

/** Pure 0..1 correction-strength and 0.5-detent rules shared by UI and service tests. */
public final class CorrectionScale {
    public static final int MIN_PERCENT = 0;
    public static final int MAX_PERCENT = 100;
    public static final int DEFAULT_PERCENT = 100;
    public static final int DETENT_PERCENT = 50;
    public static final int DETENT_WINDOW_PERCENT = 5;

    public static final class Detent {
        private final int percent;
        private final boolean snapped;

        private Detent(int percent, boolean snapped) {
            this.percent = percent;
            this.snapped = snapped;
        }

        public int getPercent() { return percent; }
        public boolean isSnapped() { return snapped; }
    }

    private CorrectionScale() {}

    public static double factor(int percent) {
        return requirePercent(percent) / 100.0;
    }

    public static Detent detentOnRelease(int percent) {
        int checked = requirePercent(percent);
        boolean snap = Math.abs(checked - DETENT_PERCENT) <= DETENT_WINDOW_PERCENT;
        return new Detent(snap ? DETENT_PERCENT : checked, snap);
    }

    public static int requirePercent(int percent) {
        if (percent < MIN_PERCENT || percent > MAX_PERCENT) {
            throw new IllegalArgumentException("correction strength must be between 0 and 100 percent");
        }
        return percent;
    }
}
