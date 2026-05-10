// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import io.ringloom.framework.config.IdleStrategyKind;

/**
 * Factory for built-in {@link IdleStrategy} implementations.
 */
public final class IdleStrategies {
    private IdleStrategies() {}

    public static IdleStrategy create(IdleStrategyKind kind) {
        return switch (kind) {
            case BUSY_SPIN -> new BusySpinIdleStrategy();
            case YIELDING -> new YieldingIdleStrategy(100);
            case SLEEPING -> new SleepingIdleStrategy(1_000_000L);
            case BACKOFF -> new BackoffIdleStrategy(100, 10, 1_000L, 1_000_000L);
            case NO_OP -> new NoOpIdleStrategy();
        };
    }
}
