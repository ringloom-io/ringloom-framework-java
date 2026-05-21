// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.metrics;

import java.util.List;

/**
 * {@link RingloomMetrics} implementation used when the native metrics reader ABI is unavailable.
 */
public final class UnavailableRingloomMetrics implements RingloomMetrics {
    /**
     * Shared singleton instance.
     */
    public static final UnavailableRingloomMetrics INSTANCE = new UnavailableRingloomMetrics();

    private UnavailableRingloomMetrics() {}

    @Override
    public RingloomCounter registerCounter(String name) {
        return UnavailableCounter.INSTANCE;
    }

    @Override
    public RingloomGauge registerGauge(String name) {
        return UnavailableGauge.INSTANCE;
    }

    @Override
    public MetricSample sample(String name) {
        throw new UnsupportedOperationException("RingLoom native metrics reader ABI is not available");
    }

    @Override
    public List<MetricSample> samples() {
        return List.of();
    }

    @Override
    public RingStats ringStats(String ringName) {
        throw new UnsupportedOperationException("RingLoom native metrics reader ABI is not available");
    }

    private enum UnavailableCounter implements RingloomCounter {
        INSTANCE;

        @Override
        public int id() {
            return -1;
        }

        @Override
        public void increment() {}

        @Override
        public void add(long delta) {}

        @Override
        public void set(long value) {}
    }

    private enum UnavailableGauge implements RingloomGauge {
        INSTANCE;

        @Override
        public int id() {
            return -1;
        }

        @Override
        public void set(long value) {}
    }
}
