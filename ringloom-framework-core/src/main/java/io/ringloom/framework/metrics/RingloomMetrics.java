// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.metrics;

import java.util.List;

/**
 * Exposes sampled metrics published by the native RingLoom runtime.
 */
public interface RingloomMetrics {
    /**
     * Registers or returns a native-backed counter handle for application updates.
     *
     * @param name the metric name
     * @return the counter handle
     */
    default RingloomCounter registerCounter(String name) {
        throw new UnsupportedOperationException("RingLoom native metrics registry is not available");
    }

    /**
     * Registers or returns a native-backed gauge handle for application updates.
     *
     * @param name the metric name
     * @return the gauge handle
     */
    default RingloomGauge registerGauge(String name) {
        throw new UnsupportedOperationException("RingLoom native metrics registry is not available");
    }

    /**
     * Returns a named metric sample.
     *
     * @param name the metric name
     * @return the sampled metric
     */
    MetricSample sample(String name);

    /**
     * Returns all currently available metric samples.
     *
     * @return the current metric samples
     */
    List<MetricSample> samples();

    /**
     * Returns occupancy statistics for a named ring buffer.
     *
     * @param ringName the ring name
     * @return the sampled ring statistics
     */
    RingStats ringStats(String ringName);
}
