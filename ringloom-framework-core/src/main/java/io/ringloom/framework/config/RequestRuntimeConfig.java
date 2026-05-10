// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Configures request/response tracking for generated client methods.
 *
 * @param maxPending the maximum number of in-flight requests
 * @param defaultTimeout the timeout applied when callers do not supply one
 * @param pooledPendingRequests whether pending requests should come from a reusable pool
 */
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

    /**
     * Returns the default request/response configuration.
     *
     * @return the framework defaults for pending request tracking
     */
    public static RequestRuntimeConfig defaults() {
        return new RequestRuntimeConfig(65_536, Duration.ofSeconds(5), true);
    }
}
