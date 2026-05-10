// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.generated;

import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.service.MessageHandler;
import io.ringloom.service.RingloomMessage;

public interface GeneratedMessageDispatcher extends MessageHandler {
    int onMessage(RingloomMessage message, MessageContext context);

    default int onContextMessage(MessageContext context) {
        return onMessage(null, context);
    }

    @Override
    default void onMessage(RingloomMessage message) {
        throw new UnsupportedOperationException("Generated dispatch requires a MessageContext");
    }
}
