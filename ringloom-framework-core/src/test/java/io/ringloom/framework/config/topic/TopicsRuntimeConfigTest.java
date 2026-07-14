// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.service.TopicConfig;
import io.ringloom.service.TopicStart;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TopicsRuntimeConfigTest {

    @Test
    void disabledIsNoOpWithDefaults() {
        TopicsRuntimeConfig config = TopicsRuntimeConfig.disabled();

        assertThat(config.enabled()).isFalse();
        assertThat(config.coalesceWithMessages()).isTrue();
        assertThat(config.prefetcher()).isEqualTo(TopicPrefetcherConfig.defaults());
        assertThat(config.publisherDefaults()).isEqualTo(TopicPublisherDefaults.defaults());
        assertThat(config.handlers()).isEmpty();
    }

    @Test
    void enabledDefaultsSetsFlag() {
        TopicsRuntimeConfig config = TopicsRuntimeConfig.enabledDefaults();

        assertThat(config.enabled()).isTrue();
        assertThat(config.prefetcher().pollLimit()).isEqualTo(64);
        assertThat(config.publisherDefaults().rollScheme()).isEqualTo("FAST_DAILY");
    }

    @Test
    void nullComponentsDefaultInCompactConstructor() {
        TopicsRuntimeConfig config = new TopicsRuntimeConfig(true, false, null, null, null);

        assertThat(config.prefetcher()).isEqualTo(TopicPrefetcherConfig.defaults());
        assertThat(config.publisherDefaults()).isEqualTo(TopicPublisherDefaults.defaults());
        assertThat(config.handlers()).isEmpty();
    }

    @Test
    void freezesHandlersMap() {
        Map<String, TopicHandlerConfig> handlers = new java.util.HashMap<>();
        handlers.put("quotes", new TopicHandlerConfig("quotes", TopicStart.EARLIEST, "fory", null));
        TopicsRuntimeConfig config = new TopicsRuntimeConfig(true, true, null, null, handlers);

        assertThat(config.handlers()).hasSize(1);
        assertThat(config.handlers().get("quotes").topic()).isEqualTo("quotes");
        assertThatThrownBy(() -> config.handlers().put("other", null))
                .isInstanceOf(UnsupportedOperationException.class);
        // Mutating the source map after construction has no effect on the config.
        handlers.put("other", null);
        assertThat(config.handlers()).hasSize(1);
    }

    @Test
    void rejectsNonPositivePollLimit() {
        assertThatThrownBy(() ->
                        new TopicsRuntimeConfig(true, true, new TopicPrefetcherConfig(null, 0, 0), null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pollLimit");
    }

    @Test
    void rejectsNegativeIntervalMicros() {
        assertThatThrownBy(() ->
                        new TopicsRuntimeConfig(true, true, new TopicPrefetcherConfig(null, 64, -1), null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intervalMicros");
    }

    @Test
    void rejectsBlankRollScheme() {
        assertThatThrownBy(() ->
                        new TopicsRuntimeConfig(true, true, null, new TopicPublisherDefaults("  ", 0, 0), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rollScheme");
    }

    @Test
    void rejectsNegativeRetentionCycles() {
        assertThatThrownBy(() -> new TopicsRuntimeConfig(
                        true, true, null, new TopicPublisherDefaults("FAST_DAILY", -1, 0), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retentionCycles");
    }

    @Test
    void publisherDefaultsRoundTripIntoTopicConfig() {
        TopicPublisherDefaults defaults = new TopicPublisherDefaults("FAST_DAILY", 7, 3);
        TopicConfig topicConfig = defaults.toTopicConfig();

        assertThat(topicConfig.rollScheme()).isEqualTo("FAST_DAILY");
        assertThat(topicConfig.retentionCycles()).isEqualTo(7);
        assertThat(topicConfig.flags()).isEqualTo(3);
    }
}
