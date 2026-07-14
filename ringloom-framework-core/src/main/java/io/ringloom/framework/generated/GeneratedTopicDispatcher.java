// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.generated;

import io.ringloom.framework.topic.TopicContext;
import io.ringloom.framework.topic.TopicMessage;

/**
 * SPI implemented by the processor-generated topic dispatcher.
 *
 * <p>The dispatcher routes inbound topic messages to {@code @RingloomTopicHandler} methods by
 * switching on {@link TopicContext#topicId()}. The inline {@code consumerThread} dispatch path calls
 * {@link #onTopicMessage(TopicMessage, TopicContext)} with the live borrowed payload view; the
 * partitioned-worker and virtual-thread paths copy the payload bytes and then call
 * {@link #onContextTopicMessage(TopicContext)} on the worker/virtual thread.
 */
public interface GeneratedTopicDispatcher {
    /**
     * Dispatches an inline topic message carrying a live borrowed payload view.
     *
     * @param message the borrowed topic message; never {@code null} on this path
     * @param context the per-thread mutable topic context, already updated from {@code message}
     * @return a {@link io.ringloom.framework.status.RingloomHandlerStatus} int
     */
    int onTopicMessage(TopicMessage message, TopicContext context);

    /**
     * Dispatches a topic message reconstructed from a copied payload.
     *
     * <p>The {@code message} argument is always {@code null} on this path; the payload and topic
     * identity are carried by {@code context}. The default delegates to {@link #onTopicMessage} with a
     * {@code null} message for handlers that do not distinguish the two entry points.
     *
     * @param context the worker/virtual-thread topic context built over the copied payload
     * @return a {@link io.ringloom.framework.status.RingloomHandlerStatus} int
     */
    default int onContextTopicMessage(TopicContext context) {
        return onTopicMessage(null, context);
    }
}
