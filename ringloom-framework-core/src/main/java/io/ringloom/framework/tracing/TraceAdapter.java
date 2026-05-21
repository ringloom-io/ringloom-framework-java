// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.tracing;

import io.ringloom.framework.annotation.RoutingMode;
import io.ringloom.framework.dispatch.MessageContext;
import java.lang.foreign.MemorySegment;

/**
 * Adapter interface for integrating application tracing with RingLoom sends and handler
 * execution.
 */
public interface TraceAdapter {
    /**
     * Returns whether an outbound send should be traced.
     *
     * @param clientName the logical generated client name
     * @param targetService the destination RingLoom service
     * @param templateId the outbound template id
     * @param routingMode the routing mode used for the send
     * @param payloadLength the encoded payload length in bytes
     * @return {@code true} when the send should be traced
     */
    default boolean shouldTraceSend(
            String clientName, String targetService, int templateId, RoutingMode routingMode, long payloadLength) {
        return true;
    }

    /**
     * Returns whether an inbound handler invocation should be traced.
     *
     * @param context the inbound message context
     * @return {@code true} when the handler invocation should be traced
     */
    default boolean shouldTraceReceive(MessageContext context) {
        return true;
    }

    /**
     * Returns the number of bytes to reserve for trace context before an encoded payload.
     *
     * @param context the outbound client trace context
     * @return the prefix length in bytes
     */
    default int payloadPrefixLength(ClientTraceContext context) {
        return 0;
    }

    /**
     * Writes trace context into a reserved payload prefix.
     *
     * @param context the outbound client trace context
     * @param scope the active send scope
     * @param prefix the target prefix segment
     */
    default void writePayloadPrefix(ClientTraceContext context, TraceScope scope, MemorySegment prefix) {}

    /**
     * Extracts trace context from an inbound payload and updates the message context.
     *
     * @param context the inbound message context
     * @return {@code true} when trace context was extracted
     */
    default boolean extractPayloadPrefix(MessageContext context) {
        return false;
    }

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
