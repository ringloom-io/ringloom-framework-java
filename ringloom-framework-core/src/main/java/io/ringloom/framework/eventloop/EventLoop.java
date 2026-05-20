// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentTerminationException;
import org.agrona.concurrent.IdleStrategy;
import org.slf4j.Logger;

/**
 * Single-threaded loop that repeatedly executes an Agrona {@link Agent} and idles between
 * iterations.
 */
public final class EventLoop implements AutoCloseable {
    private final String name;
    private final Agent agent;
    private final IdleStrategy idleStrategy;
    private final Logger logger;
    private volatile Thread thread;
    private volatile Throwable failure;

    public EventLoop(String name, Agent agent, IdleStrategy idleStrategy, Logger logger) {
        this.name = Objects.requireNonNull(name, "name");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.idleStrategy = Objects.requireNonNull(idleStrategy, "idleStrategy");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    private void runLoop() {
        try {
            agent.onStart();
            while (!Thread.currentThread().isInterrupted()) {
                int work = agent.doWork();
                idleStrategy.idle(work);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (AgentTerminationException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            failure = cause;
            logger.error("RingLoom event loop {} failed", name, cause);
        } catch (Throwable ex) {
            failure = ex;
            logger.error("RingLoom event loop {} failed", name, ex);
        } finally {
            agent.onClose();
        }
    }

    public void startThread(ThreadFactory threadFactory) {
        Objects.requireNonNull(threadFactory, "threadFactory");
        if (thread != null) {
            throw new IllegalStateException("event loop thread already started: " + name);
        }
        Thread newThread = threadFactory.newThread(this::runLoop);
        if (newThread == null) {
            throw new IllegalStateException("thread factory returned null");
        }
        newThread.setName(name);
        thread = newThread;
        newThread.start();
    }

    public Throwable failure() {
        return failure;
    }

    @Override
    public void close() {
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
