// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.topic;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.dispatch.MessageExecutionPolicy;
import io.ringloom.framework.generated.GeneratedTopicDispatcher;
import io.ringloom.service.TopicPollResult;

/**
 * Bridges the {@link TopicRuntime} poll loop to the {@link MessageExecutionPolicy} topic-dispatch
 * path.
 *
 * <p>The runtime calls {@link #offer(String, long, TopicPollResult)} for each polled message; the
 * source updates its reused {@link TopicMessage}/{@link TopicContext} and forwards to
 * {@link MessageExecutionPolicy#onTopicMessage(TopicMessage, TopicContext)}. The borrowed payload
 * contract is honoured by each policy: the {@code consumerThread} path reads the borrowed segment
 * inline, while the {@code partitionedWorkers}/{@code virtualThreads} paths copy the bytes before
 * {@code offer} returns.
 */
public final class TopicMessageSource {
    private final MessageExecutionPolicy policy;
    private final GeneratedTopicDispatcher topicDispatcher;
    private final TopicMessage message = new TopicMessage();
    private final TopicContext context;

    /**
     * Creates a new source.
     *
     * @param policy          the execution policy to forward topic messages to
     * @param topicDispatcher the generated topic dispatcher, or {@code null} when no handlers exist
     * @param runtime         the owning runtime (bound into the reused context)
     */
    public TopicMessageSource(
            MessageExecutionPolicy policy, GeneratedTopicDispatcher topicDispatcher, RingloomRuntime runtime) {
        this.policy = policy;
        this.topicDispatcher = topicDispatcher;
        this.context = new TopicContext(runtime);
    }

    /**
     * Offers one polled message to the execution policy.
     *
     * @param topicName the topic name (resolved by the runtime from the topic id)
     * @param topicId   the broker-assigned topic id
     * @param result    the borrowed poll result; its payload segment is valid only until the next poll
     *                  of the owning subscription
     */
    public void offer(String topicName, long topicId, TopicPollResult result) {
        message.updateFrom(topicId, result.payloadSegment(), result.index());
        context.updateFrom(message, topicName);
        policy.onTopicMessage(message, context);
    }

    /** Returns the generated topic dispatcher, or {@code null}. */
    public GeneratedTopicDispatcher topicDispatcher() {
        return topicDispatcher;
    }
}
