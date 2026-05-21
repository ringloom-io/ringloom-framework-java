// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import java.util.Objects;

/**
 * Configures Java-side tracing behavior for generated send and handler hooks.
 *
 * <p>Core stores this configuration without depending on OpenTelemetry. Adapter modules consume
 * the sampler and propagation settings when they implement {@code TraceAdapter}. When no tracing
 * adapter is installed, enabling this configuration alone does not start tracing.
 *
 * @param enabled whether tracing is enabled by configuration
 * @param sampler the sampling strategy used by tracing adapters
 * @param sampleRatio the trace-id ratio used when {@code sampler} is {@link
 *     TracingSamplerKind#TRACE_ID_RATIO}; ignored by other sampler kinds
 * @param propagation the trace-context propagation mode
 * @param includeDecodeTime whether receive spans should include framework decode time
 */
public record TracingRuntimeConfig(
        boolean enabled,
        TracingSamplerKind sampler,
        double sampleRatio,
        TracingPropagationMode propagation,
        boolean includeDecodeTime) {
    public TracingRuntimeConfig {
        sampler = Objects.requireNonNullElse(sampler, TracingSamplerKind.OFF);
        propagation = Objects.requireNonNullElse(propagation, TracingPropagationMode.NONE);
        if (Double.isNaN(sampleRatio) || sampleRatio < 0.0 || sampleRatio > 1.0) {
            throw new IllegalArgumentException("tracing.sampleRatio must be between 0.0 and 1.0");
        }
    }

    /**
     * Returns the default disabled tracing configuration.
     *
     * @return disabled tracing defaults
     */
    public static TracingRuntimeConfig defaults() {
        return new TracingRuntimeConfig(false, TracingSamplerKind.OFF, 0.0, TracingPropagationMode.NONE, true);
    }
}
