package org.teavm.classlib.java.util.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;

/** No thread pools in the browser: the game gives the world its own executor instead (see WebMain Tasks). */
public final class TExecutors {
    private TExecutors() {}

    public static ExecutorService newFixedThreadPool(int n, ThreadFactory factory) {
        throw new UnsupportedOperationException("no threads in the browser");
    }
}
