package dev.omshutter.solver;

/**
 * Blocking solver primitive. The coordinator confines initialization and solve calls to its
 * worker thread. {@link #close()} must be nonblocking and safe to race with solve so Android
 * lifecycle teardown never waits on native work.
 */
public interface SolverEngine extends AutoCloseable {
    final class Outcome {
        public enum Kind { SOLVED, NO_MATCH }

        private final Kind kind;
        private final SolveResult result;
        private final String detail;
        private final boolean distortionFallback;

        private Outcome(
                Kind kind, SolveResult result, String detail, boolean distortionFallback) {
            this.kind = kind;
            this.result = result;
            this.detail = detail == null ? "" : detail;
            this.distortionFallback = distortionFallback;
        }

        public static Outcome solved(
                SolveResult result, String detail, boolean distortionFallback) {
            if (result == null) throw new IllegalArgumentException("solution is null");
            return new Outcome(Kind.SOLVED, result, detail, distortionFallback);
        }

        public static Outcome noMatch(String detail, boolean distortionFallback) {
            return new Outcome(Kind.NO_MATCH, null, detail, distortionFallback);
        }

        public Kind getKind() { return kind; }
        public SolveResult getResult() { return result; }
        public String getDetail() { return detail; }
        public boolean isDistortionFallback() { return distortionFallback; }
    }

    Outcome solve(SolveRequest request) throws Exception;

    @Override
    void close();
}
