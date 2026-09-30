package org.teavm.classlib.java.util.concurrent;

/** Only named by the engine's thread-pool setup, which the browser version never runs (see TExecutors). */
public interface TThreadFactory {
    Thread newThread(Runnable r);
}
