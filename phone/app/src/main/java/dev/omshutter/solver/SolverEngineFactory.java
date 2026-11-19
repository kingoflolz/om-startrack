package dev.omshutter.solver;

/** Creates the native-backed engine lazily on the dedicated solver thread. */
public interface SolverEngineFactory {
    SolverEngine create() throws Exception;
}
