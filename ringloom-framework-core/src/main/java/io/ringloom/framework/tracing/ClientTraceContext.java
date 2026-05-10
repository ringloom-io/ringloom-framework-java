// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.tracing;

import io.ringloom.framework.annotation.RoutingMode;

public record ClientTraceContext(
    String clientName,
    String targetService,
    int templateId,
    RoutingMode routingMode,
    long payloadLength
) {
}
