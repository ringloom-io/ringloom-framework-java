// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

/**
 * Describes how trace context may be propagated across RingLoom services.
 */
public enum TracingPropagationMode {
    /**
     * Do not propagate trace context between services.
     */
    NONE,

    /**
     * Application code or serializers own trace context propagation.
     */
    APPLICATION,

    /**
     * Framework-managed serializers may prefix payloads with compact trace context.
     */
    PAYLOAD_PREFIX
}
