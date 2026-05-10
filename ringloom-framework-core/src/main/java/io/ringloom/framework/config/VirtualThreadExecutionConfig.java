// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

public record VirtualThreadExecutionConfig(int maxInFlight) {
    public VirtualThreadExecutionConfig {
        maxInFlight = maxInFlight == 0 ? 10_000 : maxInFlight;
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("virtualThreads.maxInFlight must be positive");
        }
    }

    public static VirtualThreadExecutionConfig defaults() {
        return new VirtualThreadExecutionConfig(10_000);
    }
}
