// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.metrics;

import io.ringloom.service.RingloomMetricsReader;
import io.ringloom.service.RingloomService;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link RingloomMetrics} implementation backed by the native RingLoom service metrics ABI.
 */
public final class NativeRingloomMetrics implements RingloomMetrics, AutoCloseable {
    private final RingloomService service;
    private final RingloomMetricsReader reader;
    private final Map<String, RingloomCounter> counters = new ConcurrentHashMap<>();
    private final Map<String, RingloomGauge> gauges = new ConcurrentHashMap<>();

    /**
     * Creates a metrics facade for a running low-level RingLoom service.
     *
     * @param service the low-level service
     */
    public NativeRingloomMetrics(RingloomService service) {
        this.service = Objects.requireNonNull(service, "service");
        this.reader = service.metricsReader();
    }

    @Override
    public RingloomCounter registerCounter(String name) {
        return counters.computeIfAbsent(
                name, metricName -> new NativeCounterHandle(service.registerCounter(metricName)));
    }

    @Override
    public RingloomGauge registerGauge(String name) {
        return gauges.computeIfAbsent(name, metricName -> new NativeGaugeHandle(service.registerGauge(metricName)));
    }

    @Override
    public MetricSample sample(String name) {
        Objects.requireNonNull(name, "name");
        for (MetricSample sample : samples()) {
            if (sample.name().equals(name)) {
                return sample;
            }
        }
        throw new NoSuchElementException("unknown RingLoom metric " + name);
    }

    @Override
    public List<MetricSample> samples() {
        return reader.countersSnapshot().stream()
                .map(sample -> new MetricSample(sample.name(), metricKind(sample.kind()), sample.value()))
                .toList();
    }

    @Override
    public RingStats ringStats(String ringName) {
        io.ringloom.service.RingStats stats = reader.ringStats(ringName);
        return new RingStats(
                stats.capacityBytes(),
                stats.usedBytes(),
                stats.freeBytes(),
                stats.producerPosition(),
                stats.consumerPosition());
    }

    @Override
    public void close() {
        reader.close();
    }

    private static MetricKind metricKind(io.ringloom.service.MetricKind kind) {
        return switch (kind) {
            case COUNTER -> MetricKind.COUNTER;
            case GAUGE -> MetricKind.GAUGE;
        };
    }

    private record NativeCounterHandle(io.ringloom.service.NativeCounter counter) implements RingloomCounter {
        private NativeCounterHandle {
            Objects.requireNonNull(counter, "counter");
        }

        @Override
        public int id() {
            return counter.counterId();
        }

        @Override
        public void increment() {
            counter.increment();
        }

        @Override
        public void add(long delta) {
            counter.add(delta);
        }

        @Override
        public void set(long value) {
            counter.set(value);
        }
    }

    private record NativeGaugeHandle(io.ringloom.service.NativeGauge gauge) implements RingloomGauge {
        private NativeGaugeHandle {
            Objects.requireNonNull(gauge, "gauge");
        }

        @Override
        public int id() {
            return gauge.gaugeId();
        }

        @Override
        public void set(long value) {
            gauge.set(value);
        }
    }
}
