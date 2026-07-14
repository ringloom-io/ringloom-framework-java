// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.framework.config.RuntimeMode;
import io.ringloom.framework.config.topic.TopicsRuntimeConfig;
import io.ringloom.framework.metrics.UnavailableRingloomMetrics;
import org.junit.jupiter.api.Test;

final class TopicRuntimeTest {

    @Test
    void pollTopicsReturnsZeroWhenNoSubscriptions() {
        TopicRuntime runtime = newTopicRuntime();

        assertThat(runtime.pollTopics(64)).isZero();
        assertThat(runtime.pollTopics()).isZero();
    }

    @Test
    void closeIsIdempotent() {
        TopicRuntime runtime = newTopicRuntime();

        runtime.close();
        runtime.close();

        assertThat(runtime.active()).isFalse();
    }

    @Test
    void pollAckFeedbackIsNoOpWithoutPublishers() {
        TopicRuntime runtime = newTopicRuntime();

        runtime.pollAckFeedback(); // no publishers registered → no exception

        runtime.close();
    }

    @Test
    void startWithNoSubscriptionsIsNoOp() {
        TopicRuntime runtime = newTopicRuntime();

        runtime.start(Thread.ofPlatform().name("test-prefetcher-").factory());

        runtime.close();
    }

    @Test
    void registerPublicationRequiresOpenRuntime() {
        TopicRuntime runtime = newTopicRuntime();
        runtime.close();

        assertThatThrownBy(() ->
                        runtime.registerPublication(null, "x", new io.ringloom.service.TopicConfig("FAST_DAILY", 0, 0)))
                .isInstanceOf(IllegalStateException.class);
    }

    private TopicRuntime newTopicRuntime() {
        return new TopicRuntime(
                null,
                TopicsRuntimeConfig.enabledDefaults(),
                null,
                UnavailableRingloomMetrics.INSTANCE,
                null,
                RuntimeMode.DEDICATED);
    }
}
