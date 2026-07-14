// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config.topic;

/**
 * Prefetcher-thread configuration for a topics-enabled runtime.
 *
 * <p>The prefetcher runs the ringloom-queue maintenance/cleaner work off the message poll path by
 * invoking {@code maintenancePoll} on each subscription in round-robin order. {@code cpuAffinity} is
 * optional ({@code null} ⇒ no pinning); {@code intervalMicros} controls the idle strategy
 * ({@code 0} ⇒ busy-spin, otherwise the thread parks {@code intervalMicros * 1_000} nanoseconds).
 *
 * @param cpuAffinity     optional cpu core to pin the prefetcher thread to, or {@code null}
 * @param pollLimit       maximum maintenance work units per prefetcher tick; must be {@code > 0}
 * @param intervalMicros  idle interval in microseconds; {@code 0} selects busy-spin
 */
public record TopicPrefetcherConfig(Integer cpuAffinity, int pollLimit, long intervalMicros) {
    private static final int DEFAULT_POLL_LIMIT = 64;
    private static final long DEFAULT_INTERVAL_MICROS = 0L;

    /** Defaults: no pinning, 64 work units per tick, busy-spin idle. */
    public static TopicPrefetcherConfig defaults() {
        return new TopicPrefetcherConfig(null, DEFAULT_POLL_LIMIT, DEFAULT_INTERVAL_MICROS);
    }
}
