// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import java.util.Objects;

public record RingloomEventLoopConfig(IdleStrategyKind idleStrategy, int pollLimit) {
    public static final int DEFAULT_POLL_LIMIT = 256;

    public RingloomEventLoopConfig {
        idleStrategy = Objects.requireNonNullElse(idleStrategy, IdleStrategyKind.BACKOFF);
        if (pollLimit < 0) {
            throw new IllegalArgumentException("pollLimit must be non-negative");
        }
        pollLimit = pollLimit == 0 ? DEFAULT_POLL_LIMIT : pollLimit;
    }

    public static RingloomEventLoopConfig defaults() {
        return new RingloomEventLoopConfig(IdleStrategyKind.BACKOFF, DEFAULT_POLL_LIMIT);
    }
}
