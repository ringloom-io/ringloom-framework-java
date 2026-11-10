// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import static org.assertj.core.api.Assertions.assertThat;

import io.ringloom.framework.config.kv.KvStoreRuntimeConfig;
import io.ringloom.framework.metrics.MetricKind;
import io.ringloom.framework.metrics.MetricSample;
import io.ringloom.framework.metrics.RingStats;
import io.ringloom.framework.metrics.RingloomCounter;
import io.ringloom.framework.metrics.RingloomGauge;
import io.ringloom.framework.metrics.RingloomMetrics;
import io.ringloom.service.KvWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

final class KvStoreMetricsTest {
    private final RecordingMetrics metrics = new RecordingMetrics();

    @Test
    void readerMetricsUseKvStoreNamesAndReportFrameworkStates() {
        KvReaderStore reader = new KvReaderStore("prices", KvStoreRuntimeConfig.defaults());
        reader.attachMetrics(new KvStoreMetrics.Reader(metrics, "prices"));

        assertThat(metrics.values.keySet())
                .containsExactlyInAnyOrder(
                        "kv.prices.reader.state",
                        "kv.prices.reader.lag_entries",
                        "kv.prices.reader.heartbeat_age_ms",
                        "kv.prices.reader.generation",
                        "kv.prices.reader.applied_index",
                        "kv.prices.reader.sessions",
                        "kv.prices.reader.decode_errors");
        assertThat(metrics.kinds.get("kv.prices.reader.decode_errors")).isEqualTo(MetricKind.COUNTER);
        assertThat(metrics.kinds.get("kv.prices.reader.state")).isEqualTo(MetricKind.GAUGE);

        reader.recordDecodeError();
        reader.recordDecodeError();
        assertThat(reader.refreshStatus()).isZero();
        assertThat(metrics.values.get("kv.prices.reader.state")).isEqualTo(KvStoreMetrics.READER_STATE_PENDING);
        assertThat(metrics.values.get("kv.prices.reader.decode_errors")).isEqualTo(2);
        assertThat(metrics.values.get("kv.prices.reader.sessions")).isZero();

        reader.fail(new IllegalStateException("boom"));
        reader.refreshStatus();
        assertThat(metrics.values.get("kv.prices.reader.state")).isEqualTo(KvStoreMetrics.READER_STATE_FAILED);

        reader.markClosing();
        reader.refreshStatus();
        assertThat(metrics.values.get("kv.prices.reader.state")).isEqualTo(KvStoreMetrics.READER_STATE_CLOSED);
    }

    @Test
    void writerMetricsTrackStateEpochTransitionsAndUseKvAckNames() {
        FakeKvWriterPort port = new FakeKvWriterPort();
        port.state = KvWriter.QUEUED;
        port.writerEpoch = 0;
        KvWriterStore writer = new KvWriterStore(
                "prices",
                FakeKvWriterPort.CONFIG,
                KvStoreRuntimeConfig.defaults(),
                metrics,
                LoggerFactory.getLogger(KvStoreMetricsTest.class),
                event -> {});

        assertThat(metrics.values.keySet())
                .contains(
                        "kv.prices.writer.state",
                        "kv.prices.writer.epoch",
                        "kv.prices.writer.transitions",
                        "kv.prices.writer.ack_pending",
                        "kv.prices.writer.ack_replicated_count",
                        "kv.prices.writer.ack_completed",
                        "kv.prices.writer.ack_timeout",
                        "kv.prices.writer.ack_leader_changed",
                        "kv.prices.writer.ack_shutdown",
                        "kv.prices.writer.ack_revoked")
                .noneMatch(name -> name.startsWith("topic."));
        assertThat(metrics.kinds.get("kv.prices.writer.transitions")).isEqualTo(MetricKind.COUNTER);

        writer.attach(port);
        writer.recordState(KvWriter.GRANTED, 9);
        writer.afterControlPoll();
        writer.refreshMetrics();

        assertThat(metrics.values.get("kv.prices.writer.state")).isEqualTo(KvWriterState.GRANTED.ordinal());
        assertThat(metrics.values.get("kv.prices.writer.epoch")).isEqualTo(9);
        assertThat(metrics.values.get("kv.prices.writer.transitions")).isEqualTo(2);
    }

    @Test
    void readerStateValuesAreAboveNativeStates() {
        assertThat(KvStoreMetrics.readerStateValue(KvStoreState.PENDING)).isEqualTo(100);
        assertThat(KvStoreMetrics.readerStateValue(KvStoreState.FAILED)).isEqualTo(101);
        assertThat(KvStoreMetrics.readerStateValue(KvStoreState.CLOSED)).isEqualTo(102);
    }

    /** Records registered metric names, kinds and the last written value. */
    static final class RecordingMetrics implements RingloomMetrics {
        final Map<String, Long> values = new ConcurrentHashMap<>();
        final Map<String, MetricKind> kinds = new ConcurrentHashMap<>();

        @Override
        public RingloomCounter registerCounter(String name) {
            kinds.put(name, MetricKind.COUNTER);
            values.putIfAbsent(name, 0L);
            return new RingloomCounter() {
                @Override
                public int id() {
                    return name.hashCode();
                }

                @Override
                public void increment() {
                    values.merge(name, 1L, Long::sum);
                }

                @Override
                public void add(long delta) {
                    values.merge(name, delta, Long::sum);
                }

                @Override
                public void set(long value) {
                    values.put(name, value);
                }
            };
        }

        @Override
        public RingloomGauge registerGauge(String name) {
            kinds.put(name, MetricKind.GAUGE);
            values.putIfAbsent(name, 0L);
            return new RingloomGauge() {
                @Override
                public int id() {
                    return name.hashCode();
                }

                @Override
                public void set(long value) {
                    values.put(name, value);
                }
            };
        }

        @Override
        public MetricSample sample(String name) {
            return new MetricSample(name, kinds.get(name), values.get(name));
        }

        @Override
        public List<MetricSample> samples() {
            return values.keySet().stream().map(this::sample).toList();
        }

        @Override
        public RingStats ringStats(String ringName) {
            throw new UnsupportedOperationException();
        }
    }
}
