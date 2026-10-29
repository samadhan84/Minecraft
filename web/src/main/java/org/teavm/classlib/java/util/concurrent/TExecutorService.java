package org.teavm.classlib.java.util.concurrent;

import java.util.concurrent.Executor;

/** Only named by the engine's thread-pool setup, which the browser version never runs (see TExecutors). */
public interface TExecutorService extends Executor {
    void shutdown();
}
