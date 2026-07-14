// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config.topic;

import io.ringloom.service.TopicConfig;

/**
 * Default native {@link TopicConfig} applied when a {@code @RingloomTopicPublisher} does not override
 * the roll scheme or retention.
 *
 * @param rollScheme       ringloom-queue roll scheme name (1..16 UTF-8 bytes)
 * @param retentionCycles  retained ring cycles; {@code 0} keeps all
 * @param flags            opaque native topic flags
 */
public record TopicPublisherDefaults(String rollScheme, int retentionCycles, int flags) {
    private static final String DEFAULT_ROLL_SCHEME = "FAST_DAILY";

    /** Defaults: {@code FAST_DAILY} roll scheme, keep all cycles, no flags. */
    public static TopicPublisherDefaults defaults() {
        return new TopicPublisherDefaults(DEFAULT_ROLL_SCHEME, 0, 0);
    }

    /**
     * Builds the native topic config from these defaults.
     *
     * @return a {@link TopicConfig} mirroring these defaults
     */
    public TopicConfig toTopicConfig() {
        return new TopicConfig(rollScheme, retentionCycles, flags);
    }
}
