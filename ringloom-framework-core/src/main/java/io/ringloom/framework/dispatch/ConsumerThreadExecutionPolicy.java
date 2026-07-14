// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.framework.generated.GeneratedTopicDispatcher;
import io.ringloom.framework.request.RequestResponseRegistry;
import io.ringloom.framework.topic.TopicContext;
import io.ringloom.framework.topic.TopicMessage;
import io.ringloom.service.RingloomMessage;
import java.util.Objects;

/**
 * Message execution policy that invokes handlers directly on the consumer thread.
 *
 * <p>Topic messages are dispatched inline with the live borrowed payload view (zero-alloc, zero-copy).
 */
public final class ConsumerThreadExecutionPolicy implements MessageExecutionPolicy {
    private final GeneratedMessageDispatcher dispatcher;
    private final RequestResponseRegistry requestRegistry;
    private volatile GeneratedTopicDispatcher topicDispatcher;

    public ConsumerThreadExecutionPolicy(
            GeneratedMessageDispatcher dispatcher, RequestResponseRegistry requestRegistry) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.requestRegistry = Objects.requireNonNull(requestRegistry, "requestRegistry");
    }

    /**
     * Wires the generated topic dispatcher. When {@code null} (no {@code @RingloomTopicHandler}),
     * {@link #onTopicMessage} is never invoked by the runtime.
     */
    public void topicDispatcher(GeneratedTopicDispatcher topicDispatcher) {
        this.topicDispatcher = topicDispatcher;
    }

    @Override
    public int onMessage(RingloomMessage message, MessageContext ingressContext) {
        ingressContext.updateFrom(message);
        return dispatcher.onMessage(message, ingressContext);
    }

    @Override
    public int onTopicMessage(TopicMessage message, TopicContext ingressContext) {
        GeneratedTopicDispatcher topicDispatcher = this.topicDispatcher;
        if (topicDispatcher == null) {
            return io.ringloom.framework.status.RingloomHandlerStatus.UNKNOWN_TOPIC;
        }
        return topicDispatcher.onTopicMessage(message, ingressContext);
    }
}
