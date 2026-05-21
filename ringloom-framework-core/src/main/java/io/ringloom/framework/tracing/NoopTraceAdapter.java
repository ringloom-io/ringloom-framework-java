// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.tracing;

import io.ringloom.framework.annotation.RoutingMode;
import io.ringloom.framework.dispatch.MessageContext;

/**
 * No-op tracing adapter used when tracing is disabled.
 */
public final class NoopTraceAdapter implements TraceAdapter {
    /**
     * Shared singleton instance.
     */
    public static final NoopTraceAdapter INSTANCE = new NoopTraceAdapter();

    private static final TraceScope SCOPE = () -> {};

    private NoopTraceAdapter() {}

    @Override
    public boolean shouldTraceSend(
            String clientName, String targetService, int templateId, RoutingMode routingMode, long payloadLength) {
        return false;
    }

    @Override
    public boolean shouldTraceReceive(MessageContext context) {
        return false;
    }

    @Override
    public TraceScope onSendStart(ClientTraceContext context) {
        return SCOPE;
    }

    @Override
    public TraceScope onReceiveStart(MessageContext context) {
        return SCOPE;
    }

    @Override
    public void onSendComplete(ClientTraceContext context, int status) {}

    @Override
    public void onHandlerComplete(MessageContext context, int status) {}
}
