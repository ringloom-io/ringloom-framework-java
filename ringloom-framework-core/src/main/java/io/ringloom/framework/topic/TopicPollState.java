// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.topic;

import io.ringloom.service.TopicPollResult;
import io.ringloom.service.TopicSubscription;

/**
 * Reusable per-subscription poll state. Preallocated once at registration time and reused across
 * every {@code pollTopics()} tick to keep the steady-state hot path zero-allocation.
 *
 * <p>The {@link TopicPollResult} is owned by this state and reused as the out-parameter for
 * {@link TopicSubscription#poll(TopicPollResult)}; its borrowed payload view is valid only until the
 * next poll of this subscription.
 */
final class TopicPollState {
    final TopicSubscription subscription;
    final long topicId;
    final String topicName;
    final TopicPollResult result = new TopicPollResult();

    TopicPollState(TopicSubscription subscription, long topicId, String topicName) {
        this.subscription = subscription;
        this.topicId = topicId;
        this.topicName = topicName;
    }
}
