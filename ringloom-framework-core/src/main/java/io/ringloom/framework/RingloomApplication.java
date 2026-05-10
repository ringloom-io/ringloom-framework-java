// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework;

import java.util.Objects;

/**
 * Running RingLoom application handle returned by {@link RingloomBootstrap#start()}.
 */
public final class RingloomApplication implements AutoCloseable {
    private final RingloomRuntime runtime;

    /**
     * Creates an application handle for a started runtime.
     *
     * @param runtime the started runtime
     */
    public RingloomApplication(RingloomRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
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
        runtime.close();
    }
}
