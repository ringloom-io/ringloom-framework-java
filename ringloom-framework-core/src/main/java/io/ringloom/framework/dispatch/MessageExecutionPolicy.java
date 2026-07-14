// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import io.ringloom.framework.topic.TopicContext;
import io.ringloom.framework.topic.TopicMessage;
import io.ringloom.service.RingloomMessage;

/**
 * Dispatches inbound RingLoom messages according to a configured execution model.
 *
 * <p>The default {@link #onTopicMessage(TopicMessage, TopicContext)} throws to signal that a policy
 * does not support topic messages; the three concrete policies override it. Topic dispatch is only
 * invoked when the runtime has a wired {@link io.ringloom.framework.topic.TopicMessageSource}.
 */
public interface MessageExecutionPolicy extends AutoCloseable {
    int onMessage(RingloomMessage message, MessageContext ingressContext);

    /**
     * Dispatches an inbound topic message carrying a borrowed payload view.
     *
     * <p>The {@code message}/{@code context} are reused per poll thread and already updated by the
     * caller ({@link io.ringloom.framework.topic.TopicMessageSource#offer}). The default
     * implementation throws to signal that topic dispatch is unsupported.
     *
     * @param message         the borrowed topic message
     * @param ingressContext the per-thread mutable topic context
     * @return a {@link io.ringloom.framework.status.RingloomHandlerStatus} int
     */
    default int onTopicMessage(TopicMessage message, TopicContext ingressContext) {
        throw new UnsupportedOperationException("policy does not support topics");
    }

    @Override
    default void close() {}
}
