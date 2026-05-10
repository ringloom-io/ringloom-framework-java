// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.tracing;

import io.ringloom.framework.annotation.RoutingMode;

/**
 * Describes an outbound client send for tracing integrations.
 *
 * @param clientName the logical generated client name
 * @param targetService the destination RingLoom service
 * @param templateId the outbound template id
 * @param routingMode the routing mode used for the send
 * @param payloadLength the encoded payload length in bytes
 */
public record ClientTraceContext(
        String clientName, String targetService, int templateId, RoutingMode routingMode, long payloadLength) {}
