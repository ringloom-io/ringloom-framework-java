// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.tracing;

import io.ringloom.framework.dispatch.MessageContext;

public final class NoopTraceAdapter implements TraceAdapter {
    public static final NoopTraceAdapter INSTANCE = new NoopTraceAdapter();
    private static final TraceScope SCOPE = () -> {
    };

    private NoopTraceAdapter() {
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
    public void onSendComplete(ClientTraceContext context, int status) {
    }

    @Override
    public void onHandlerComplete(MessageContext context, int status) {
    }
}
