// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import java.util.Objects;

/**
 * Configures an event loop's idle strategy and poll limit.
 *
 * @param idleStrategy the idle strategy used between polls
 * @param pollLimit the maximum units of work to poll per loop iteration
 * @param cpuCore optional zero-based CPU core to pin the event-loop thread to
 */
public record RingloomEventLoopConfig(IdleStrategyKind idleStrategy, int pollLimit, Integer cpuCore) {
    /**
     * Default maximum work units polled per iteration.
     */
    public static final int DEFAULT_POLL_LIMIT = 256;

    public RingloomEventLoopConfig {
        idleStrategy = Objects.requireNonNullElse(idleStrategy, IdleStrategyKind.BACKOFF);
        if (pollLimit < 0) {
            throw new IllegalArgumentException("pollLimit must be non-negative");
        }
        if (cpuCore != null && (cpuCore < 0 || cpuCore > 1023)) {
            throw new IllegalArgumentException("cpuCore must be between 0 and 1023");
        }
        pollLimit = pollLimit == 0 ? DEFAULT_POLL_LIMIT : pollLimit;
    }

    public RingloomEventLoopConfig(IdleStrategyKind idleStrategy, int pollLimit) {
        this(idleStrategy, pollLimit, null);
    }

    /**
     * Returns the default event-loop configuration.
     *
     * @return the framework defaults for event-loop polling
     */
    public static RingloomEventLoopConfig defaults() {
        return new RingloomEventLoopConfig(IdleStrategyKind.BACKOFF, DEFAULT_POLL_LIMIT, null);
    }
}
