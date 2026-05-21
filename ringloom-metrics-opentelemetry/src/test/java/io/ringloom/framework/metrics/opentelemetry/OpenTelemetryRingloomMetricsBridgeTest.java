// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.metrics.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.ringloom.framework.metrics.MetricKind;
import io.ringloom.framework.metrics.MetricSample;
import io.ringloom.framework.metrics.RingStats;
import io.ringloom.framework.metrics.RingloomMetrics;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class OpenTelemetryRingloomMetricsBridgeTest {
    private SdkMeterProvider meterProvider;

    @AfterEach
    void closeMeterProvider() {
        if (meterProvider != null) {
            meterProvider.close();
        }
    }

    @Test
    void registersExistingCounterAndGaugeSamples() {
        // Given
        TestMetrics metrics = new TestMetrics();
        metrics.put("orders.sent", MetricKind.COUNTER, 12);
        metrics.put("queue.depth", MetricKind.GAUGE, 3);
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OpenTelemetryRingloomMetricsBridge bridge = bridge(reader, metrics);

        // When
        bridge.registerExistingMetrics();
        Collection<MetricData> collected = reader.collectAllMetrics();

        // Then
        assertThat(longSum(collected, "orders.sent")).isEqualTo(12);
        assertThat(longGauge(collected, "queue.depth")).isEqualTo(3);
    }

    @Test
    void callbacksReadLatestRingloomMetricValues() {
        // Given
        TestMetrics metrics = new TestMetrics();
        metrics.put("orders.sent", MetricKind.COUNTER, 12);
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OpenTelemetryRingloomMetricsBridge bridge = bridge(reader, metrics);
        bridge.registerCounter("orders.sent");

        // When
        assertThat(longSum(reader.collectAllMetrics(), "orders.sent")).isEqualTo(12);
        metrics.put("orders.sent", MetricKind.COUNTER, 19);

        // Then
        assertThat(longSum(reader.collectAllMetrics(), "orders.sent")).isEqualTo(19);
    }

    @Test
    void closeUnregistersInstruments() {
        // Given
        TestMetrics metrics = new TestMetrics();
        metrics.put("queue.depth", MetricKind.GAUGE, 3);
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OpenTelemetryRingloomMetricsBridge bridge = bridge(reader, metrics);
        bridge.registerGauge("queue.depth");

        // When
        bridge.close();

        // Then
        assertThat(reader.collectAllMetrics()).isEmpty();
    }

    @Test
    void rejectsConflictingMetricKindForSameName() {
        // Given
        TestMetrics metrics = new TestMetrics();
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        OpenTelemetryRingloomMetricsBridge bridge = bridge(reader, metrics);
        bridge.registerCounter("orders.sent");

        // When / Then
        assertThatThrownBy(() -> bridge.registerGauge("orders.sent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("metric 'orders.sent' is already registered as COUNTER");
    }

    @Test
    void builderRequiresMeterAndMetrics() {
        // Given / When / Then
        assertThatThrownBy(() -> OpenTelemetryRingloomMetricsBridge.builder().build())
                .isInstanceOf(NullPointerException.class)
                .hasMessage("meter");
    }

    private OpenTelemetryRingloomMetricsBridge bridge(InMemoryMetricReader reader, RingloomMetrics metrics) {
        meterProvider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        OpenTelemetry openTelemetry =
                OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build();
        return OpenTelemetryRingloomMetricsBridge.builder()
                .meter(openTelemetry.getMeter("ringloom-test"))
                .metrics(metrics)
                .build();
    }

    private static long longSum(Collection<MetricData> metrics, String name) {
        return metric(metrics, name)
                .getLongSumData()
                .getPoints()
                .iterator()
                .next()
                .getValue();
    }

    private static long longGauge(Collection<MetricData> metrics, String name) {
        return metric(metrics, name)
                .getLongGaugeData()
                .getPoints()
                .iterator()
                .next()
                .getValue();
    }

    private static MetricData metric(Collection<MetricData> metrics, String name) {
        return metrics.stream()
                .filter(metric -> metric.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static final class TestMetrics implements RingloomMetrics {
        private final Map<String, MetricSample> samples = new ConcurrentHashMap<>();

        void put(String name, MetricKind kind, long value) {
            samples.put(name, new MetricSample(name, kind, value));
        }

        @Override
        public MetricSample sample(String name) {
            MetricSample sample = samples.get(name);
            if (sample == null) {
                throw new NoSuchElementException(name);
            }
            return sample;
        }

        @Override
        public List<MetricSample> samples() {
            return List.copyOf(samples.values());
        }

        @Override
        public RingStats ringStats(String ringName) {
            throw new UnsupportedOperationException();
        }
    }
}
