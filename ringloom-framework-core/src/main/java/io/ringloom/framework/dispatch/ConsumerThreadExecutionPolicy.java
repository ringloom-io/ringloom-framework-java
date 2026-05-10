// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.framework.request.RequestResponseRegistry;
import io.ringloom.service.RingloomMessage;
import java.util.Objects;

/**
 * Message execution policy that invokes handlers directly on the consumer thread.
 */
public final class ConsumerThreadExecutionPolicy implements MessageExecutionPolicy {
    private final GeneratedMessageDispatcher dispatcher;
    private final RequestResponseRegistry requestRegistry;

    public ConsumerThreadExecutionPolicy(
            GeneratedMessageDispatcher dispatcher, RequestResponseRegistry requestRegistry) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.requestRegistry = Objects.requireNonNull(requestRegistry, "requestRegistry");
    }

    @Override
    public int onMessage(RingloomMessage message, MessageContext ingressContext) {
        ingressContext.updateFrom(message);
        return dispatcher.onMessage(message, ingressContext);
    }
}
