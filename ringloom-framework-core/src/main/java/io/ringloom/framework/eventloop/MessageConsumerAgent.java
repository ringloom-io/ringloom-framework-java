// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.framework.dispatch.MessageExecutionPolicy;
import io.ringloom.service.MessageConsumer;
import io.ringloom.service.MessageHandler;
import io.ringloom.service.RingloomMessage;
import java.util.Objects;
import org.agrona.concurrent.Agent;

/**
 * Agent that polls inbound messages and forwards them to the configured execution policy.
 */
public final class MessageConsumerAgent implements Agent, MessageHandler {
    private final String roleName;
    private final MessageConsumer consumer;
    private final MessageExecutionPolicy executionPolicy;
    private final MessageContext context;
    private final int pollLimit;
    private final Runnable topicPollHook;

    public MessageConsumerAgent(
            MessageConsumer consumer, MessageExecutionPolicy executionPolicy, RingloomRuntime runtime, int pollLimit) {
        this("ringloom-message-consumer-agent", consumer, executionPolicy, runtime, pollLimit, null);
    }

    public MessageConsumerAgent(
            String roleName,
            MessageConsumer consumer,
            MessageExecutionPolicy executionPolicy,
            RingloomRuntime runtime,
            int pollLimit) {
        this(roleName, consumer, executionPolicy, runtime, pollLimit, null);
    }

    /**
     * Constructs a message-consumer agent with an optional topic-poll hook invoked after each message
     * batch (used for {@code topics.coalesceWithMessages}).
     *
     * @param roleName       the agent role name
     * @param consumer       the native message consumer
     * @param executionPolicy the execution policy
     * @param runtime        the owning runtime
     * @param pollLimit      the per-tick message poll limit
     * @param topicPollHook  invoked after each {@code consumer.poll} batch, or {@code null}
     */
    public MessageConsumerAgent(
            String roleName,
            MessageConsumer consumer,
            MessageExecutionPolicy executionPolicy,
            RingloomRuntime runtime,
            int pollLimit,
            Runnable topicPollHook) {
        this.roleName = Objects.requireNonNullElse(roleName, "ringloom-message-consumer-agent");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.executionPolicy = Objects.requireNonNull(executionPolicy, "executionPolicy");
        this.context = new MessageContext(runtime);
        if (pollLimit < 0) {
            throw new IllegalArgumentException("pollLimit must be non-negative");
        }
        this.pollLimit = pollLimit;
        this.topicPollHook = topicPollHook;
    }

    @Override
    public int doWork() {
        int messages = consumer.poll(this, pollLimit);
        if (topicPollHook != null) {
            topicPollHook.run();
        }
        return messages;
    }

    @Override
    public void onMessage(RingloomMessage message) {
        executionPolicy.onMessage(message, context);
    }

    @Override
    public String roleName() {
        return roleName;
    }
}
