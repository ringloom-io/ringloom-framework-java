// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.request;

import java.time.Duration;

/**
 * Positive timeout value used by generated request APIs.
 *
 * @param duration the timeout duration
 */
public record RequestTimeout(Duration duration) {
    public RequestTimeout {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("duration must be positive");
        }
    }

    public static RequestTimeout ofMillis(long millis) {
        return new RequestTimeout(Duration.ofMillis(millis));
    }
}
