// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import io.ringloom.framework.topic.TopicContext;
import io.ringloom.framework.topic.TopicMessage;

/**
 * Extracts the partition key for partitioned topic-message execution, keyed by the broker-assigned
 * topic id.
 *
 * <p>When no extractor is registered for a topic, the runtime falls back to the topic id itself as the
 * key so all messages for a keyless topic route to a single worker (preserving per-topic order).
 */
@FunctionalInterface
public interface TopicPartitionKeyExtractor {
    /**
     * Extracts the partition key for a topic message.
     *
     * @param message the borrowed topic message
     * @param context the per-thread mutable topic context
     * @return the partition key
     */
    long partitionKey(TopicMessage message, TopicContext context);
}
