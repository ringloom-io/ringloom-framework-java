// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.metrics.opentelemetry;

import io.opentelemetry.api.metrics.Meter;
import io.ringloom.framework.metrics.MetricKind;
import io.ringloom.framework.metrics.MetricSample;
import io.ringloom.framework.metrics.RingloomMetrics;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registers OpenTelemetry asynchronous instruments backed by {@link RingloomMetrics} samples.
 */
public final class OpenTelemetryRingloomMetricsBridge implements AutoCloseable {
    private final Meter meter;
    private final RingloomMetrics metrics;
    private final Map<String, Instrument> instruments = new HashMap<>();

    /**
     * Creates an OpenTelemetry metrics bridge.
     *
     * @param meter the OpenTelemetry meter
     * @param metrics the RingLoom metrics facade
     */
    public OpenTelemetryRingloomMetricsBridge(Meter meter, RingloomMetrics metrics) {
        this.meter = Objects.requireNonNull(meter, "meter");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Creates a bridge builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Registers asynchronous instruments for all currently visible RingLoom samples.
     *
     * @return this bridge
     */
    public OpenTelemetryRingloomMetricsBridge registerExistingMetrics() {
        for (MetricSample sample : metrics.samples()) {
            register(sample.name(), sample.kind());
        }
        return this;
    }

    /**
     * Registers an asynchronous counter backed by a RingLoom metric sample.
     *
     * @param name the metric name
     * @return this bridge
     */
    public OpenTelemetryRingloomMetricsBridge registerCounter(String name) {
        return register(name, MetricKind.COUNTER);
    }

    /**
     * Registers an asynchronous gauge backed by a RingLoom metric sample.
     *
     * @param name the metric name
     * @return this bridge
     */
    public OpenTelemetryRingloomMetricsBridge registerGauge(String name) {
        return register(name, MetricKind.GAUGE);
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (Instrument instrument : instruments.values()) {
            try {
                instrument.closeable().close();
            } catch (Exception ex) {
                if (failure == null) {
                    failure = new RuntimeException("failed to close OpenTelemetry metric instrument", ex);
                } else {
                    failure.addSuppressed(ex);
                }
            }
        }
        instruments.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private OpenTelemetryRingloomMetricsBridge register(String name, MetricKind kind) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Instrument existing = instruments.get(name);
        if (existing != null) {
            if (existing.kind() != kind) {
                throw new IllegalArgumentException("metric '" + name + "' is already registered as " + existing.kind());
            }
            return this;
        }
        instruments.put(name, new Instrument(kind, instrument(name, kind)));
        return this;
    }

    private AutoCloseable instrument(String name, MetricKind kind) {
        return switch (kind) {
            case COUNTER ->
                meter.counterBuilder(name)
                        .setDescription("RingLoom native counter " + name)
                        .buildWithCallback(measurement ->
                                measurement.record(metrics.sample(name).value()));
            case GAUGE ->
                meter.gaugeBuilder(name)
                        .ofLongs()
                        .setDescription("RingLoom native gauge " + name)
                        .buildWithCallback(measurement ->
                                measurement.record(metrics.sample(name).value()));
        };
    }

    private record Instrument(MetricKind kind, AutoCloseable closeable) {}

    /**
     * Builder for {@link OpenTelemetryRingloomMetricsBridge}.
     */
    public static final class Builder {
        private Meter meter;
        private RingloomMetrics metrics;

        private Builder() {}

        /**
         * Sets the OpenTelemetry meter.
         *
         * @param meter the meter
         * @return this builder
         */
        public Builder meter(Meter meter) {
            this.meter = Objects.requireNonNull(meter, "meter");
            return this;
        }

        /**
         * Sets the RingLoom metrics facade.
         *
         * @param metrics the metrics facade
         * @return this builder
         */
        public Builder metrics(RingloomMetrics metrics) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            return this;
        }

        /**
         * Builds the metrics bridge.
         *
         * @return the metrics bridge
         */
        public OpenTelemetryRingloomMetricsBridge build() {
            return new OpenTelemetryRingloomMetricsBridge(
                    Objects.requireNonNull(meter, "meter"), Objects.requireNonNull(metrics, "metrics"));
        }
    }
}
