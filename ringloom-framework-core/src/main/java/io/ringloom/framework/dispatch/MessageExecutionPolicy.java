// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import io.ringloom.service.RingloomMessage;

public interface MessageExecutionPolicy extends AutoCloseable {
    int onMessage(RingloomMessage message, MessageContext ingressContext);

    @Override
    default void close() {
    }
}
