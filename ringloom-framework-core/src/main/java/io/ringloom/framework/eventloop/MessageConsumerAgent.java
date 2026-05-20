// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.framework.dispatch.MessageExecutionPolicy;
import io.ringloom.service.MessageConsumer;
import java.util.Objects;
import org.agrona.concurrent.Agent;

/**
 * Agent that polls inbound messages and forwards them to the configured execution policy.
 */
public final class MessageConsumerAgent implements Agent {
    private final String roleName;
    private final MessageConsumer consumer;
    private final MessageExecutionPolicy executionPolicy;
    private final MessageContext context;
    private final int pollLimit;

    public MessageConsumerAgent(
            MessageConsumer consumer, MessageExecutionPolicy executionPolicy, RingloomRuntime runtime, int pollLimit) {
        this("ringloom-message-consumer-agent", consumer, executionPolicy, runtime, pollLimit);
    }

    public MessageConsumerAgent(
            String roleName,
            MessageConsumer consumer,
            MessageExecutionPolicy executionPolicy,
            RingloomRuntime runtime,
            int pollLimit) {
        this.roleName = Objects.requireNonNullElse(roleName, "ringloom-message-consumer-agent");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.executionPolicy = Objects.requireNonNull(executionPolicy, "executionPolicy");
        this.context = new MessageContext(runtime);
        if (pollLimit < 0) {
            throw new IllegalArgumentException("pollLimit must be non-negative");
        }
        this.pollLimit = pollLimit;
    }

    @Override
    public int doWork() {
        return consumer.poll(message -> executionPolicy.onMessage(message, context), pollLimit);
    }

    @Override
    public String roleName() {
        return roleName;
    }
}
