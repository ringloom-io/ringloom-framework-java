// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.tracing;

import io.ringloom.framework.dispatch.MessageContext;

/**
 * Adapter interface for integrating application tracing with RingLoom sends and handler
 * execution.
 */
public interface TraceAdapter {
    /**
     * Starts a tracing scope for an outbound send.
     *
     * @param context the outbound client trace context
     * @return the active tracing scope
     */
    TraceScope onSendStart(ClientTraceContext context);

    /**
     * Starts a tracing scope for an inbound message.
     *
     * @param context the inbound message context
     * @return the active tracing scope
     */
    TraceScope onReceiveStart(MessageContext context);

    /**
     * Completes tracing for an outbound send.
     *
     * @param context the outbound client trace context
     * @param status the RingLoom status returned by the send
     */
    void onSendComplete(ClientTraceContext context, int status);

    /**
     * Completes tracing for a handler invocation.
     *
     * @param context the inbound message context
     * @param status the RingLoom handler status
     */
    void onHandlerComplete(MessageContext context, int status);
}
