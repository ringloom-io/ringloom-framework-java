// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/**
 * Single-threaded loop that repeatedly executes an {@link Agent} and idles between iterations.
 */
public final class EventLoop implements AutoCloseable, Runnable {
    private final String name;
    private final Agent agent;
    private final IdleStrategy idleStrategy;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;
    private volatile Throwable failure;

    public EventLoop(String name, Agent agent, IdleStrategy idleStrategy, Logger logger) {
        this.name = Objects.requireNonNull(name, "name");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.idleStrategy = Objects.requireNonNull(idleStrategy, "idleStrategy");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void run() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("event loop already running: " + name);
        }
        try {
            agent.onStart();
            while (running.get()) {
                int work = agent.doWork();
                idleStrategy.idle(work);
            }
        } catch (Throwable ex) {
            failure = ex;
            logger.error("RingLoom event loop {} failed", name, ex);
        } finally {
            try {
                agent.onClose();
            } finally {
                running.set(false);
            }
        }
    }

    public void startThread(ThreadFactory threadFactory) {
        Objects.requireNonNull(threadFactory, "threadFactory");
        if (thread != null) {
            throw new IllegalStateException("event loop thread already started: " + name);
        }
        Thread newThread = threadFactory.newThread(this);
        if (newThread == null) {
            throw new IllegalStateException("thread factory returned null");
        }
        thread = newThread;
        newThread.start();
    }

    public Throwable failure() {
        return failure;
    }

    @Override
    public void close() {
        running.set(false);
        Thread ownedThread = thread;
        if (ownedThread != null && ownedThread != Thread.currentThread()) {
            ownedThread.interrupt();
            try {
                ownedThread.join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
