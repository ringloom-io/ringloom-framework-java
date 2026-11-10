// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import io.ringloom.framework.metrics.MetricSample;
import io.ringloom.framework.metrics.RingStats;
import io.ringloom.framework.metrics.RingloomCounter;
import io.ringloom.framework.metrics.RingloomGauge;
import io.ringloom.framework.metrics.RingloomMetrics;
import io.ringloom.service.KvStatusHolder;
import java.util.List;
import java.util.Objects;

/**
 * Native-backed metric handles of one KV store, named {@code kv.<store>.<metric>}. Handles are
 * {@code null} when the runtime metrics registry is unavailable; every update is then a no-op.
 *
 * <p>Reader gauges ({@code reader.state}, {@code reader.lag_entries}, {@code reader.heartbeat_age_ms},
 * {@code reader.generation}, {@code reader.applied_index}, {@code reader.sessions}) and the
 * {@code reader.decode_errors} counter, and the writer gauges ({@code writer.state},
 * {@code writer.epoch}) and {@code writer.transitions} counter are refreshed on the control thread
 * every {@code statusIntervalMillis}; updates are plain slot stores and allocate nothing. Writer ack
 * metrics ({@code writer.ack_pending}, {@code writer.ack_completed}, ...) are maintained by the
 * writer's ack registry through {@link #ackMetrics(RingloomMetrics, String)}.
 *
 * <p>{@code reader.state} carries the native reader state (0..4) while the store is open, or
 * {@link #READER_STATE_PENDING}, {@link #READER_STATE_FAILED}, {@link #READER_STATE_CLOSED} for the
 * framework states.
 */
final class KvStoreMetrics {
    /** {@code reader.state} value while the framework reader is {@link KvStoreState#PENDING}. */
    static final long READER_STATE_PENDING = 100;
    /** {@code reader.state} value while the framework reader is {@link KvStoreState#FAILED}. */
    static final long READER_STATE_FAILED = 101;
    /** {@code reader.state} value once the framework reader is {@link KvStoreState#CLOSED}. */
    static final long READER_STATE_CLOSED = 102;

    private static final String TOPIC_ACK_PREFIX = "topic.ack.";

    private KvStoreMetrics() {}

    /**
     * Returns the metric name prefix of a store, {@code kv.<store>.}.
     *
     * @param store the store name
     * @return the prefix
     */
    static String prefix(String store) {
        return "kv." + store + ".";
    }

    /**
     * Returns a metrics view that registers the ack registry's {@code topic.ack.<name>} metrics as
     * {@code kv.<store>.writer.ack_<name>} so KV writers do not share the topic ack metric slots.
     *
     * @param metrics the runtime metrics
     * @param store   the store name
     * @return the renaming view
     */
    static RingloomMetrics ackMetrics(RingloomMetrics metrics, String store) {
        return new AckMetrics(Objects.requireNonNull(metrics, "metrics"), prefix(store) + "writer.ack_");
    }

    static RingloomGauge gauge(RingloomMetrics metrics, String name) {
        try {
            return metrics.registerGauge(name);
        } catch (UnsupportedOperationException ex) {
            return null;
        }
    }

    static RingloomCounter counter(RingloomMetrics metrics, String name) {
        try {
            return metrics.registerCounter(name);
        } catch (UnsupportedOperationException ex) {
            return null;
        }
    }

    static void set(RingloomGauge gauge, long value) {
        if (gauge != null) {
            gauge.set(value);
        }
    }

    static void set(RingloomCounter counter, long value) {
        if (counter != null) {
            counter.set(value);
        }
    }

    /** Reader metric handles. */
    static final class Reader {
        private final RingloomGauge state;
        private final RingloomGauge lagEntries;
        private final RingloomGauge heartbeatAgeMillis;
        private final RingloomGauge generation;
        private final RingloomGauge appliedIndex;
        private final RingloomGauge sessions;
        private final RingloomCounter decodeErrors;

        Reader(RingloomMetrics metrics, String store) {
            String p = prefix(store) + "reader.";
            this.state = gauge(metrics, p + "state");
            this.lagEntries = gauge(metrics, p + "lag_entries");
            this.heartbeatAgeMillis = gauge(metrics, p + "heartbeat_age_ms");
            this.generation = gauge(metrics, p + "generation");
            this.appliedIndex = gauge(metrics, p + "applied_index");
            this.sessions = gauge(metrics, p + "sessions");
            this.decodeErrors = counter(metrics, p + "decode_errors");
        }

        /** Publishes the native status of an open reader. */
        void updateOpen(KvStatusHolder status, int sessionCount, long decodeErrorCount) {
            set(state, status.state());
            set(lagEntries, status.lagEntries());
            set(heartbeatAgeMillis, status.heartbeatAgeMillis());
            set(generation, status.generation());
            set(appliedIndex, status.appliedIndex());
            set(sessions, sessionCount);
            set(decodeErrors, decodeErrorCount);
        }

        /** Publishes a framework (not open) state. */
        void updateNotOpen(KvStoreState framework, int sessionCount, long decodeErrorCount) {
            set(state, readerStateValue(framework));
            set(sessions, sessionCount);
            set(decodeErrors, decodeErrorCount);
        }
    }

    /** Writer metric handles (ack metrics are owned by the ack registry). */
    static final class Writer {
        private final RingloomGauge state;
        private final RingloomGauge epoch;
        private final RingloomCounter transitions;

        Writer(RingloomMetrics metrics, String store) {
            String p = prefix(store) + "writer.";
            this.state = gauge(metrics, p + "state");
            this.epoch = gauge(metrics, p + "epoch");
            this.transitions = counter(metrics, p + "transitions");
        }

        void update(KvWriterState writerState, long writerEpoch, long transitionCount) {
            set(state, writerState.ordinal());
            set(epoch, writerEpoch);
            set(transitions, transitionCount);
        }
    }

    static long readerStateValue(KvStoreState framework) {
        return switch (framework) {
            case PENDING -> READER_STATE_PENDING;
            case FAILED -> READER_STATE_FAILED;
            case CLOSED -> READER_STATE_CLOSED;
            case OPEN -> throw new IllegalArgumentException("open readers report the native state");
        };
    }

    /** Renames {@code topic.ack.<name>} registrations to {@code <prefix><name>}. */
    private record AckMetrics(RingloomMetrics delegate, String prefix) implements RingloomMetrics {
        private String rename(String name) {
            return name.startsWith(TOPIC_ACK_PREFIX) ? prefix + name.substring(TOPIC_ACK_PREFIX.length()) : name;
        }

        @Override
        public RingloomCounter registerCounter(String name) {
            return delegate.registerCounter(rename(name));
        }

        @Override
        public RingloomGauge registerGauge(String name) {
            return delegate.registerGauge(rename(name));
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
    }
}
