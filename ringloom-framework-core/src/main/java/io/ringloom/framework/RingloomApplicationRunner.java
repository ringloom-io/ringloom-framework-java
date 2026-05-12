// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Running RingLoom application runner returned by {@link RingloomBootstrap#start()}.
 */
public final class RingloomApplicationRunner implements AutoCloseable {

    private final RingloomRuntime runtime;
    private final Thread shutdownHook;
    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);

    /**
     * Creates an application runner for a started runtime.
     *
     * @param runtime the started runtime
     */
    public RingloomApplicationRunner(RingloomRuntime runtime) {
        this(runtime, false, "ringloom");
    }

    /**
     * Creates an application runner and optionally installs a JVM shutdown hook.
     *
     * @param runtime the started runtime
     * @param installShutdownHook whether to install a shutdown hook that closes the runtime
     * @param shutdownHookName the base thread name used for the shutdown hook when enabled
     */
    public RingloomApplicationRunner(RingloomRuntime runtime, boolean installShutdownHook, String shutdownHookName) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.shutdownHook = installShutdownHook
                ? new Thread(runtime::close, Objects.requireNonNull(shutdownHookName, "shutdownHookName") + "-shutdown")
                : null;
        if (shutdownHook != null) {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            shutdownHookRegistered.set(true);
        }
    }

    /**
     * Returns the underlying runtime.
     *
     * @return the wrapped runtime
     */
    public RingloomRuntime runtime() {
        return runtime;
    }

    /**
     * Blocks until the application has shut down.
     *
     * <p>When the runtime lifecycle config enables a shutdown hook, this method can be used in a
     * simple `main(...)` method to wait until a JVM termination signal triggers shutdown.
     *
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void awaitShutdown() throws InterruptedException {
        runtime.awaitShutdown();
    }

    /**
     * Stops the application runtime.
     */
    @Override
    public void close() {
        unregisterShutdownHook();
        runtime.close();
    }

    private void unregisterShutdownHook() {
        if (shutdownHook == null || !shutdownHookRegistered.compareAndSet(true, false)) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown is already in progress.
        }
    }
}
