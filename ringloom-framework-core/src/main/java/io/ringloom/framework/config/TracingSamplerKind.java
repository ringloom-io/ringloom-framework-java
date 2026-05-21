// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

/**
 * Configures the sampling strategy used by tracing adapters.
 */
public enum TracingSamplerKind {
    /**
     * Disable tracing at the sampler level.
     */
    OFF,

    /**
     * Sample every eligible send and receive.
     */
    ALWAYS_ON,

    /**
     * Sample according to a stable trace-id ratio.
     */
    TRACE_ID_RATIO
}
