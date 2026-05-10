// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.metrics;

import java.util.List;

public interface RingloomMetrics {
    MetricSample sample(String name);

    List<MetricSample> samples();

    RingStats ringStats(String ringName);
}
