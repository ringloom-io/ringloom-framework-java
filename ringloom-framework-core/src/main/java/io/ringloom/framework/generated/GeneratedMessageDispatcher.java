// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.generated;

import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.service.MessageHandler;
import io.ringloom.service.RingloomMessage;

/**
 * SPI implemented by generated dispatcher classes produced by the annotation processor.
 */
public interface GeneratedMessageDispatcher extends MessageHandler {
    /**
     * Dispatches an inbound message using a prepared message context.
     *
     * @param message the low-level RingLoom message, or {@code null} when dispatching from copied
     *     context
     * @param context the context describing the message being dispatched
     * @return the RingLoom handler status code
     */
    int onMessage(RingloomMessage message, MessageContext context);

    /**
     * Dispatches a copied message context that no longer carries the original {@link
     * RingloomMessage}.
     *
     * @param context the copied message context
     * @return the RingLoom handler status code
     */
    default int onContextMessage(MessageContext context) {
        return onMessage(null, context);
    }

    /**
     * This SPI requires {@link MessageContext}, so the plain low-level callback is unsupported.
     *
     * @param message the low-level message
     */
    @Override
    default void onMessage(RingloomMessage message) {
        throw new UnsupportedOperationException("Generated dispatch requires a MessageContext");
    }
}
