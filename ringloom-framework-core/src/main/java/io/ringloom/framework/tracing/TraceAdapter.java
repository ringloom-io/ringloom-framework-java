// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.tracing;

import io.ringloom.framework.dispatch.MessageContext;

public interface TraceAdapter {
    TraceScope onSendStart(ClientTraceContext context);

    TraceScope onReceiveStart(MessageContext context);

    void onSendComplete(ClientTraceContext context, int status);

    void onHandlerComplete(MessageContext context, int status);
}
