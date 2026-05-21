// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.metrics;

import io.ringloom.service.RingloomService;
import java.util.List;
import java.util.Objects;

/**
 * Runtime-owned metrics facade that attaches to the native service during startup.
 */
public final class RuntimeRingloomMetrics implements RingloomMetrics, AutoCloseable {
    private volatile RingloomMetrics delegate = UnavailableRingloomMetrics.INSTANCE;

    /**
     * Attaches this facade to a running native service.
     *
     * @param service the low-level service
     */
    public void attach(RingloomService service) {
        Objects.requireNonNull(service, "service");
        RingloomMetrics current = delegate;
        if (current instanceof NativeRingloomMetrics) {
            throw new IllegalStateException("RingLoom runtime metrics are already attached");
        }
        delegate = new NativeRingloomMetrics(service);
    }

    @Override
    public RingloomCounter registerCounter(String name) {
        return delegate.registerCounter(name);
    }

    @Override
    public RingloomGauge registerGauge(String name) {
        return delegate.registerGauge(name);
    }

    @Override
    public MetricSample sample(String name) {
        return delegate.sample(name);
    }

    @Override
    public List<MetricSample> samples() {
        return delegate.samples();
    }

    @Override
    public RingStats ringStats(String ringName) {
        return delegate.ringStats(ringName);
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
        delegate = UnavailableRingloomMetrics.INSTANCE;
    }
}
