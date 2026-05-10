// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.metrics;

public record MetricSample(String name, MetricKind kind, long value) {
}
