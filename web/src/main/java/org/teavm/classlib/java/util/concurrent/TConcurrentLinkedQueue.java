package org.teavm.classlib.java.util.concurrent;

import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * The browser runs the game on one thread, so the queues the engine shares between threads on phones and PCs
 * can be a plain queue here. TeaVM uses this class wherever the game says java.util.concurrent.ConcurrentLinkedQueue.
 */
public class TConcurrentLinkedQueue<E> extends AbstractQueue<E> {
    private final ArrayDeque<E> items = new ArrayDeque<>();

    public TConcurrentLinkedQueue() {}

    @Override public boolean offer(E e) { items.addLast(e); return true; }
    @Override public E poll() { return items.pollFirst(); }
    @Override public E peek() { return items.peekFirst(); }
    @Override public int size() { return items.size(); }
    @Override public Iterator<E> iterator() { return items.iterator(); }
}
