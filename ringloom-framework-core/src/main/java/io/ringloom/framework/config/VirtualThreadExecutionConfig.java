// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

/**
 * Configures virtual-thread dispatch for inbound messages.
 *
 * @param maxInFlight the maximum number of messages allowed to execute concurrently
 */
public record VirtualThreadExecutionConfig(int maxInFlight) {
    public VirtualThreadExecutionConfig {
        maxInFlight = maxInFlight == 0 ? 10_000 : maxInFlight;
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("virtualThreads.maxInFlight must be positive");
        }
    }

    /**
     * Returns the default virtual-thread execution configuration.
     *
     * @return the framework defaults for virtual-thread dispatch
     */
    public static VirtualThreadExecutionConfig defaults() {
        return new VirtualThreadExecutionConfig(10_000);
    }
}
