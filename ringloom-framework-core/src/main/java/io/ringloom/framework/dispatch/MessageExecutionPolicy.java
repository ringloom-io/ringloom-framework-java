// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import io.ringloom.service.RingloomMessage;

/**
 * Dispatches inbound RingLoom messages according to a configured execution model.
 */
public interface MessageExecutionPolicy extends AutoCloseable {
    int onMessage(RingloomMessage message, MessageContext ingressContext);

    @Override
    default void close() {}
}
