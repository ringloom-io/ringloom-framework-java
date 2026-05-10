// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.metrics;

/**
 * Represents a single metric sample returned by the runtime metrics facade.
 *
 * @param name the metric name
 * @param kind the metric kind
 * @param value the sampled metric value
 */
public record MetricSample(String name, MetricKind kind, long value) {}
