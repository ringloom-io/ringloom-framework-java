// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.TestBroker;
import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.config.RingloomRuntimeConfig;
import io.ringloom.framework.config.RingloomServiceRuntimeConfig;
import io.ringloom.framework.config.RuntimeMode;
import io.ringloom.framework.config.kv.KvRuntimeConfig;
import io.ringloom.framework.config.kv.KvStoreRuntimeConfig;
import io.ringloom.framework.generated.GeneratedClientBinding;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.framework.generated.GeneratedRingloomApplication;
import io.ringloom.framework.metrics.MetricKind;
import io.ringloom.framework.metrics.MetricSample;
import io.ringloom.framework.metrics.RuntimeRingloomMetrics;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.framework.status.RingloomHandlerStatus;
import io.ringloom.framework.topic.ack.AckStatus;
import io.ringloom.service.RingloomStatus;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for KV metrics (§10.3), health status and configured-store validation against
 * a single-node KV-enabled test broker. Activated by {@code -Pringloom.brokerBin=...} and
 * {@code -Pringloom.repoRoot=...}; skipped otherwise.
 */
@EnabledIfSystemProperty(named = "ringloom.brokerBin", matches = ".+")
final class KvMetricsIntegrationTest {
    private static final Path REPO_ROOT =
            Path.of(System.getProperty("ringloom.repoRoot", "/home/dragan/code/ringloom"));
    private static final List<String> STORE_METRICS = List.of(
            "kv.prices.reader.state",
            "kv.prices.reader.lag_entries",
            "kv.prices.reader.heartbeat_age_ms",
            "kv.prices.reader.generation",
            "kv.prices.reader.applied_index",
            "kv.prices.reader.sessions",
            "kv.prices.reader.decode_errors",
            "kv.prices.writer.state",
            "kv.prices.writer.epoch",
            "kv.prices.writer.ack_pending",
            "kv.prices.writer.transitions");

    private final List<RingloomRuntime> runtimes = new ArrayList<>();
    private TestBroker broker;
    private Path workspace;
    private Arena arena;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createTempDirectory("ringloom-kv-metrics-it-");
        broker = TestBroker.startKvEnabled(REPO_ROOT, workspace);
        arena = Arena.ofShared();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int i = runtimes.size() - 1; i >= 0; i--) {
            runtimes.get(i).close();
        }
        runtimes.clear();
        if (broker != null) {
            broker.close();
        }
        arena.close();
    }

    @Test
    void kvMetricsArePublishedAfterStartAndRefreshedOnTheControlThread() throws Exception {
        RingloomRuntime runtime = runtime(
                "kv-metrics", Map.of("prices", KvStoreRuntimeConfig.defaults().withRoles(true, true)));
        runtime.start();

        Map<String, MetricSample> samples = samples(runtime);
        assertThat(samples).containsKeys(STORE_METRICS.toArray(String[]::new));
        assertThat(samples.keySet()).noneMatch(name -> name.startsWith("topic.ack."));
        assertThat(samples.get("kv.prices.reader.decode_errors").kind()).isEqualTo(MetricKind.COUNTER);
        assertThat(samples.get("kv.prices.writer.transitions").kind()).isEqualTo(MetricKind.COUNTER);
        assertThat(samples.get("kv.prices.writer.state").kind()).isEqualTo(MetricKind.GAUGE);
        assertThat(samples.get("kv.prices.writer.state").value()).isEqualTo(KvWriterState.GRANTED.ordinal());
        assertThat(samples.get("kv.prices.writer.epoch").value()).isPositive();

        KvRuntime kv = runtime.kv();
        KvWriterStore writer = kv.writer("prices");
        KvReaderStore reader = kv.reader("prices");
        MemorySegment key = arena.allocate(8);
        key.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 1L);
        MemorySegment value = arena.allocate(4);
        AtomicReference<AckStatus> ack = new AtomicReference<>();
        assertThat(writer.putAck(key, 0, 8, value, 0, 4, KvTtl.NEVER, (seq, status, ctx) -> ack.set(status), null))
                .isEqualTo(RingloomStatus.OK);
        pollUntil(runtime, () -> ack.get() == AckStatus.ACKED, 5000);
        pollUntil(runtime, () -> reader.contains(key, 0, 8) == RingloomStatus.OK, 5000);

        // Reader gauges follow the native status after the next status refresh (100 ms here).
        pollUntil(
                runtime,
                () -> {
                    Map<String, MetricSample> now = samples(runtime);
                    return now.get("kv.prices.reader.state").value() == KvStoreStatus.NATIVE_LIVE
                            && now.get("kv.prices.reader.applied_index").value() > 0
                            && now.get("kv.prices.reader.sessions").value() >= 1
                            && now.get("kv.prices.writer.transitions").value() >= 1;
                },
                5000);
        pollUntil(runtime, () -> kv.status("prices").healthy(), 5000);
        KvStoreStatus status = kv.status("prices");
        assertThat(status.readerState()).isEqualTo(KvStoreState.OPEN);
        assertThat(status.writerState()).isEqualTo(KvWriterState.GRANTED);
        assertThat(status.statusIntervalMillis()).isEqualTo(100);
    }

    @Test
    void pendingReaderReportsFrameworkStateInTheStateGauge() throws Exception {
        RingloomRuntime runtime = runtime(
                "kv-metrics-pending",
                Map.of("later", KvStoreRuntimeConfig.defaults().withRoles(true, false)));
        runtime.start();

        assertThat(runtime.kv().reader("later").state()).isEqualTo(KvStoreState.PENDING);
        assertThat(samples(runtime).get("kv.later.reader.state").value())
                .isEqualTo(KvStoreMetrics.READER_STATE_PENDING);
        assertThat(runtime.kv().status("later").healthy()).isFalse();
    }

    @Test
    void configuredStoreWithoutBindingOrRawRoleFailsStartup() {
        RingloomRuntime runtime = runtime("kv-unknown", Map.of("orphan", KvStoreRuntimeConfig.defaults()));

        assertThatThrownBy(runtime::start)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("unknown kv store ringloom.kv.stores.orphan");
    }

    private static Map<String, MetricSample> samples(RingloomRuntime runtime) {
        return runtime.metrics().samples().stream()
                .collect(Collectors.toMap(MetricSample::name, Function.identity(), (a, b) -> b));
    }

    private RingloomRuntime runtime(String serviceName, Map<String, KvStoreRuntimeConfig> stores) {
        String storage = broker.storagePath() != null
                ? broker.storagePath()
                : workspace.resolve("storage").toString();
        RingloomApplicationConfig config = new RingloomApplicationConfig(
                new RingloomServiceRuntimeConfig(
                        serviceName, storage, broker.group(), broker.nodeId(), false, 0, 0, 0, false),
                new RingloomRuntimeConfig(RuntimeMode.EXTERNAL, null, null, null, null, null, false, null),
                null,
                Map.of(),
                null,
                new KvRuntimeConfig(true, 100, 100, 1000, stores));
        RingloomRuntime runtime = new RingloomRuntime(
                config,
                new EmptyApplication(serviceName),
                SerializerRegistry.EMPTY,
                new RuntimeRingloomMetrics(),
                LoggerFactory.getLogger("kv-metrics-it." + serviceName));
        runtimes.add(runtime);
        return runtime;
    }

    private static void pollUntil(RingloomRuntime runtime, BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeoutMillis + " ms");
            }
            runtime.pollControl();
            Thread.sleep(5);
        }
    }

    private static final class EmptyApplication implements GeneratedRingloomApplication {
        private final String serviceName;

        EmptyApplication(String serviceName) {
            this.serviceName = serviceName;
        }

        @Override
        public String serviceName() {
            return serviceName;
        }

        @Override
        public List<GeneratedClientBinding<?>> clients() {
            return List.of();
        }

        @Override
        public GeneratedMessageDispatcher dispatcher() {
            return (message, context) -> RingloomHandlerStatus.OK;
        }
    }
}
