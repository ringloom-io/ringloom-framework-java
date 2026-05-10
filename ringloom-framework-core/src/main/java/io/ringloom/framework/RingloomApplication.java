// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework;

import java.util.Objects;

public final class RingloomApplication implements AutoCloseable {
    private final RingloomRuntime runtime;

    public RingloomApplication(RingloomRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public RingloomRuntime runtime() {
        return runtime;
    }

    public void awaitShutdown() throws InterruptedException {
        runtime.awaitShutdown();
    }

    @Override
    public void close() {
        runtime.close();
    }
}
