// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.metrics;

import java.util.List;

public final class UnavailableRingloomMetrics implements RingloomMetrics {
    public static final UnavailableRingloomMetrics INSTANCE = new UnavailableRingloomMetrics();

    private UnavailableRingloomMetrics() {
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
}
