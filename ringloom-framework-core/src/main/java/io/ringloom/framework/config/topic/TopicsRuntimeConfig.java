// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config.topic;

import java.util.Map;

/**
 * Runtime configuration for persistent topics.
 *
 * <p>When {@code enabled == false} (the default) the runtime treats topics as a complete no-op: no
 * publishers/subscriptions are registered, the prefetcher thread is not started, and
 * {@code pollTopics()} returns zero. When {@code enabled == true} the runtime owns topic publishers
 * and subscriptions, runs the maintenance prefetcher, and coalesces topic polling onto the message
 * thread when {@code coalesceWithMessages} is set (ignored in {@code EXTERNAL} runtime mode).
 *
 * @param enabled             whether persistent topics are enabled
 * @param coalesceWithMessages whether topic polling runs on the message thread (ignored in {@code EXTERNAL} mode)
 * @param prefetcher          prefetcher-thread configuration
 * @param publisherDefaults   default native {@link io.ringloom.service.TopicConfig} for generated publishers
 * @param handlers            per-topic handler overrides keyed by topic name
 */
public record TopicsRuntimeConfig(
        boolean enabled,
        boolean coalesceWithMessages,
        TopicPrefetcherConfig prefetcher,
        TopicPublisherDefaults publisherDefaults,
        Map<String, TopicHandlerConfig> handlers) {

    /** Disabled no-op configuration used when topics are not requested. */
    public static TopicsRuntimeConfig disabled() {
        return new TopicsRuntimeConfig(
                false, true, TopicPrefetcherConfig.defaults(), TopicPublisherDefaults.defaults(), Map.of());
    }

    /** Enabled configuration with framework defaults and no handler overrides. */
    public static TopicsRuntimeConfig enabledDefaults() {
        return new TopicsRuntimeConfig(
                true, true, TopicPrefetcherConfig.defaults(), TopicPublisherDefaults.defaults(), Map.of());
    }

    public TopicsRuntimeConfig {
        prefetcher = prefetcher == null ? TopicPrefetcherConfig.defaults() : prefetcher;
        publisherDefaults = publisherDefaults == null ? TopicPublisherDefaults.defaults() : publisherDefaults;
        handlers = handlers == null ? Map.of() : Map.copyOf(handlers);
        if (prefetcher.pollLimit() <= 0) {
            throw new IllegalArgumentException("topics.prefetcher.pollLimit must be > 0");
        }
        if (prefetcher.intervalMicros() < 0) {
            throw new IllegalArgumentException("topics.prefetcher.intervalMicros must be >= 0");
        }
        if (publisherDefaults.rollScheme() == null
                || publisherDefaults.rollScheme().isBlank()) {
            throw new IllegalArgumentException("topics.publisherDefaults.rollScheme must be non-blank");
        }
        if (publisherDefaults.retentionCycles() < 0) {
            throw new IllegalArgumentException("topics.publisherDefaults.retentionCycles must be >= 0");
        }
    }
}
