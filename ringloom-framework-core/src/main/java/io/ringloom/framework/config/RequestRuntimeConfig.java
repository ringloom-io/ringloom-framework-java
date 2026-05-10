// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import java.time.Duration;
import java.util.Objects;

public record RequestRuntimeConfig(int maxPending, Duration defaultTimeout, boolean pooledPendingRequests) {
    public RequestRuntimeConfig {
        maxPending = maxPending == 0 ? 65_536 : maxPending;
        defaultTimeout = defaultTimeout == null ? Duration.ofSeconds(5) : defaultTimeout;
        if (maxPending <= 0) {
            throw new IllegalArgumentException("requests.maxPending must be positive");
        }
        if (Objects.requireNonNull(defaultTimeout, "defaultTimeout").isNegative() || defaultTimeout.isZero()) {
            throw new IllegalArgumentException("requests.defaultTimeout must be positive");
        }
    }

    public static RequestRuntimeConfig defaults() {
        return new RequestRuntimeConfig(65_536, Duration.ofSeconds(5), true);
    }
}
