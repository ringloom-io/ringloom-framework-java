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
import io.ringloom.framework.config.kv.KvAcquireMode;
import io.ringloom.framework.config.kv.KvRuntimeConfig;
import io.ringloom.framework.config.kv.KvStoreConfigOverride;
import io.ringloom.framework.config.kv.KvStoreRuntimeConfig;
import io.ringloom.framework.config.kv.KvTtlClock;
import io.ringloom.framework.generated.GeneratedClientBinding;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.framework.generated.GeneratedRingloomApplication;
import io.ringloom.framework.metrics.UnavailableRingloomMetrics;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.framework.status.RingloomHandlerStatus;
import io.ringloom.framework.topic.ack.AckStatus;
import io.ringloom.service.KvStatusHolder;
import io.ringloom.service.RingloomService;
import io.ringloom.service.RingloomStatus;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for {@link KvRuntime} against a single-node KV-enabled test broker.
 *
 * <p>Activated by {@code -Pringloom.brokerBin=...} and {@code -Pringloom.repoRoot=...}; skipped
 * otherwise.
 */
@EnabledIfSystemProperty(named = "ringloom.brokerBin", matches = ".+")
final class KvRuntimeIntegrationTest {
    private static final Path REPO_ROOT =
            Path.of(System.getProperty("ringloom.repoRoot", "/home/dragan/code/ringloom"));

    private final List<RingloomRuntime> runtimes = new ArrayList<>();
    private TestBroker broker;
    private Path workspace;
    private Arena arena;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createTempDirectory("ringloom-kv-it-");
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
    void writerAndReaderInOneRuntime() throws Exception {
        RingloomRuntime runtime = runtime(
                "kv-rw",
                RuntimeMode.EXTERNAL,
                fastConfig(Map.of("prices", KvStoreRuntimeConfig.defaults().withRoles(true, true))));
        runtime.start();
        KvRuntime kv = runtime.kv();
        assertThat(kv.stores()).containsExactly("prices");
        KvWriterStore writer = kv.writer("prices");
        KvReaderStore reader = kv.reader("prices");
        assertThat(writer.state()).isEqualTo(KvWriterState.GRANTED);
        assertThat(writer.writerEpoch()).isPositive();
        assertThat(writer.config()).isNotNull();

        MemorySegment key = longKey(42);
        MemorySegment value = bytes("hello");
        assertThat(writer.put(key, 0, 8, value, 0, value.byteSize(), KvTtl.NEVER))
                .isEqualTo(RingloomStatus.OK);

        AtomicReference<AckStatus> ack = new AtomicReference<>();
        MemorySegment key2 = longKey(43);
        assertThat(writer.putAck(key2, 0, 8, value, 0, 3, KvTtl.NEVER, (seq, status, ctx) -> ack.set(status), null))
                .isEqualTo(RingloomStatus.OK);
        pollUntil(runtime, () -> ack.get() != null, 5000);
        assertThat(ack.get()).isEqualTo(AckStatus.ACKED);

        pollUntil(runtime, () -> reader.state() == KvStoreState.OPEN, 5000);
        MemorySegment destination = arena.allocate(64);
        long[] length = new long[1];
        pollUntil(runtime, () -> reader.get(key2, 0, 8, destination, 0, 64, length) == RingloomStatus.OK, 5000);
        assertThat(reader.get(key, 0, 8, destination, 0, 64, length)).isEqualTo(RingloomStatus.OK);
        assertThat(length[0]).isEqualTo(5);
        assertThat(destination.asSlice(0, 5).toArray(ValueLayout.JAVA_BYTE)).isEqualTo("hello".getBytes());
        assertThat(reader.get(key, 0, 8, destination, 0, 2, length)).isEqualTo(RingloomStatus.KV_BUFFER_TOO_SMALL);
        assertThat(length[0]).isEqualTo(5);
        assertThat(reader.contains(key, 0, 8)).isEqualTo(RingloomStatus.OK);
        assertThat(reader.contains(longKey(99), 0, 8)).isEqualTo(RingloomStatus.KV_MISSING);

        AtomicInteger visited = new AtomicInteger();
        assertThat(reader.forEach((k, v) -> visited.incrementAndGet() > 0)).isEqualTo(RingloomStatus.OK);
        assertThat(visited.get()).isEqualTo(2);
        AtomicInteger rawVisited = new AtomicInteger();
        assertThat(reader.forEachRaw((ka, kl, va, vl) -> rawVisited.incrementAndGet() > 0))
                .isEqualTo(RingloomStatus.OK);
        assertThat(rawVisited.get()).isEqualTo(2);

        // Reads from a virtual thread use the session pool.
        CompletableFuture<Integer> virtualRead = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            long[] out = new long[1];
            virtualRead.complete(reader.get(key, 0, 8, arena.allocate(64), 0, 64, out));
        });
        assertThat(virtualRead.get(5, TimeUnit.SECONDS)).isEqualTo(RingloomStatus.OK);

        KvStatusHolder holder = new KvStatusHolder();
        assertThat(reader.status(holder)).isEqualTo(RingloomStatus.OK);
        pollUntil(runtime, reader::isReady, 3000);
        assertThat(kv.status("prices").readerState()).isEqualTo(KvStoreState.OPEN);
        assertThat(kv.status("prices").writerState()).isEqualTo(KvWriterState.GRANTED);

        assertThat(writer.delete(key, 0, 8)).isEqualTo(RingloomStatus.OK);
        pollUntil(runtime, () -> reader.contains(key, 0, 8) == RingloomStatus.KV_MISSING, 5000);

        assertThat(writer.beginBatch()).isEqualTo(RingloomStatus.OK);
        for (int i = 0; i < 100; i++) {
            assertThat(writer.put(longKey(1000 + i), 0, 8, value, 0, 5, KvTtl.NEVER))
                    .isEqualTo(RingloomStatus.OK);
        }
        AtomicReference<AckStatus> batchAck = new AtomicReference<>();
        assertThat(writer.commitBatchAck((seq, status, ctx) -> batchAck.set(status), null))
                .isEqualTo(RingloomStatus.OK);
        pollUntil(runtime, () -> batchAck.get() != null, 5000);
        assertThat(batchAck.get()).isEqualTo(AckStatus.ACKED);
        pollUntil(runtime, () -> reader.contains(longKey(1099), 0, 8) == RingloomStatus.OK, 5000);

        assertThat(writer.clear()).isEqualTo(RingloomStatus.OK);
        pollUntil(runtime, () -> reader.contains(key2, 0, 8) == RingloomStatus.KV_MISSING, 5000);

        runtime.close();
        assertThat(reader.get(key, 0, 8, destination, 0, 64, length)).isEqualTo(RingloomHandlerStatus.SHUTDOWN);
        assertThat(writer.put(key, 0, 8, value, 0, 5, KvTtl.NEVER)).isEqualTo(RingloomHandlerStatus.SHUTDOWN);
        assertThat(writer.state()).isEqualTo(KvWriterState.CLOSED);
    }

    @Test
    void pendingReaderOpensAfterWriterAppears() throws Exception {
        RingloomRuntime readerRuntime = runtime(
                "kv-reader",
                RuntimeMode.EXTERNAL,
                fastConfig(Map.of("late", KvStoreRuntimeConfig.defaults().withRoles(true, false))));
        readerRuntime.start();
        KvReaderStore reader = readerRuntime.kv().reader("late");
        assertThat(reader.state()).isEqualTo(KvStoreState.PENDING);
        MemorySegment key = longKey(7);
        MemorySegment destination = arena.allocate(64);
        long[] length = new long[1];
        assertThat(reader.get(key, 0, 8, destination, 0, 64, length)).isEqualTo(RingloomStatus.NOT_READY);
        assertThat(reader.contains(key, 0, 8)).isEqualTo(RingloomStatus.NOT_READY);
        readerRuntime.pollControl();

        RingloomRuntime writerRuntime = runtime(
                "kv-writer",
                RuntimeMode.EXTERNAL,
                fastConfig(Map.of("late", KvStoreRuntimeConfig.defaults().withRoles(false, true))));
        writerRuntime.start();
        KvWriterStore writer = writerRuntime.kv().writer("late");
        MemorySegment value = bytes("v1");
        assertThat(writer.put(key, 0, 8, value, 0, 2, KvTtl.NEVER)).isEqualTo(RingloomStatus.OK);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && reader.get(key, 0, 8, destination, 0, 64, length) != RingloomStatus.OK) {
            writerRuntime.pollControl();
            readerRuntime.pollControl();
            Thread.sleep(10);
        }
        assertThat(reader.state()).isEqualTo(KvStoreState.OPEN);
        assertThat(reader.get(key, 0, 8, destination, 0, 64, length)).isEqualTo(RingloomStatus.OK);
        assertThat(length[0]).isEqualTo(2);
        assertThat(reader.config()).isNotNull();
    }

    @Test
    void requiredReaderWithoutWriterFailsStartup() throws Exception {
        KvStoreRuntimeConfig required = new KvStoreRuntimeConfig(
                true,
                false,
                true,
                300,
                false,
                KvTtlClock.PRECISE,
                4,
                2,
                KvAcquireMode.FAIL_IF_HELD,
                5000,
                0,
                16,
                0,
                KvStoreConfigOverride.none());
        RingloomRuntime runtime = runtime("kv-required", RuntimeMode.EXTERNAL, fastConfig(Map.of("absent", required)));
        assertThatThrownBy(runtime::start)
                .isInstanceOf(RingloomKvException.class)
                .hasMessageContaining("absent");
    }

    @Test
    void standbyWriterIsGrantedWhenActiveWriterCloses() throws Exception {
        KvStoreRuntimeConfig failIfHeld = KvStoreRuntimeConfig.defaults().withRoles(false, true);
        RingloomRuntime active = runtime("kv-active", RuntimeMode.EXTERNAL, fastConfig(Map.of("lease", failIfHeld)));
        active.start();
        assertThat(active.kv().writer("lease").state()).isEqualTo(KvWriterState.GRANTED);

        RingloomRuntime rejected =
                runtime("kv-rejected", RuntimeMode.EXTERNAL, fastConfig(Map.of("lease", failIfHeld)));
        assertThatThrownBy(rejected::start)
                .isInstanceOf(RingloomKvException.class)
                .satisfies(ex ->
                        assertThat(((RingloomKvException) ex).statusCode()).isEqualTo(RingloomStatus.KV_HELD_BY_OTHER));
        rejected.close();

        KvStoreRuntimeConfig wait = new KvStoreRuntimeConfig(
                false,
                true,
                false,
                5000,
                false,
                KvTtlClock.PRECISE,
                4,
                2,
                KvAcquireMode.WAIT,
                5000,
                0,
                16,
                0,
                KvStoreConfigOverride.none());
        RingloomRuntime standby = runtime("kv-standby", RuntimeMode.DEDICATED, fastConfig(Map.of("lease", wait)));
        standby.start();
        List<KvWriterStateEvent> events = new CopyOnWriteArrayList<>();
        KvWriterStore standbyWriter = standby.kv().writer("lease");
        standby.kv().addWriterStateListener(events::add);
        assertThat(standbyWriter.state()).isEqualTo(KvWriterState.QUEUED);
        MemorySegment key = longKey(1);
        MemorySegment value = bytes("x");
        assertThat(standbyWriter.put(key, 0, 8, value, 0, 1, KvTtl.NEVER)).isEqualTo(RingloomStatus.KV_NOT_WRITER);
        standby.startEventLoops(Thread.ofPlatform().name("kv-standby-", 0).factory());

        active.close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && standbyWriter.state() != KvWriterState.GRANTED) {
            Thread.sleep(10);
        }
        assertThat(standbyWriter.state()).isEqualTo(KvWriterState.GRANTED);
        assertThat(events).anySatisfy(event -> {
            assertThat(event.store()).isEqualTo("lease");
            assertThat(event.state()).isEqualTo(KvWriterState.GRANTED);
        });
        assertThat(standbyWriter.put(key, 0, 8, value, 0, 1, KvTtl.NEVER)).isEqualTo(RingloomStatus.OK);
    }

    @Test
    void openReaderAfterStartRunsOnControlThread() throws Exception {
        RingloomRuntime runtime = runtime(
                "kv-open",
                RuntimeMode.EXTERNAL,
                fastConfig(Map.of("dyn", KvStoreRuntimeConfig.defaults().withRoles(false, true))));
        runtime.start();
        CompletableFuture<KvReaderStore> future = runtime.kv().openReader("dyn", KvStoreRuntimeConfig.defaults());
        assertThat(future).isNotDone();
        pollUntil(runtime, future::isDone, 5000);
        assertThat(future.get().state()).isEqualTo(KvStoreState.OPEN);
        assertThat(runtime.kv().reader("dyn")).isSameAs(future.get());
    }

    @Test
    void preStartOpenContinuationsMayOpenMoreStores() throws Exception {
        String storage = broker.storagePath() != null
                ? broker.storagePath()
                : workspace.resolve("storage").toString();
        try (RingloomService service = RingloomService.start(new RingloomServiceRuntimeConfig(
                                "kv-chain", storage, broker.group(), broker.nodeId(), false, 0, 0, 0, false)
                        .toLowLevelConfig());
                KvRuntime kv = new KvRuntime(
                        service,
                        "kv-chain",
                        fastConfig(Map.of()),
                        UnavailableRingloomMetrics.INSTANCE,
                        LoggerFactory.getLogger("kv-it.kv-chain"),
                        null)) {
            KvStoreRuntimeConfig settings = KvStoreRuntimeConfig.defaults();
            CompletableFuture<KvWriterStore> base = kv.openWriter("chain-a", null, settings);
            CompletableFuture<KvReaderStore> baseReader = kv.openReader("chain-a", settings);
            // Synchronous continuations: they run on whichever thread completes the base futures.
            CompletableFuture<KvWriterStore> chainedWriter =
                    base.thenCompose(writer -> kv.openWriter("chain-b", null, settings));
            CompletableFuture<KvReaderStore> chainedReader =
                    baseReader.thenCompose(reader -> kv.openReader("chain-a", settings));

            kv.start();

            assertThat(base).isCompleted();
            assertThat(baseReader).isCompleted();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!(chainedWriter.isDone() && chainedReader.isDone())) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("chained opens did not complete");
                }
                service.pollControl();
                kv.afterControlPoll();
                Thread.sleep(5);
            }
            assertThat(chainedWriter.get().storeName()).isEqualTo("chain-b");
            assertThat(kv.writer("chain-b")).isSameAs(chainedWriter.get());
            assertThat(chainedWriter.get().state()).isEqualTo(KvWriterState.GRANTED);
            assertThat(chainedReader.get()).isSameAs(baseReader.get());
            assertThat(chainedReader.get().state()).isEqualTo(KvStoreState.OPEN);
        }
    }

    private static KvRuntimeConfig fastConfig(Map<String, KvStoreRuntimeConfig> stores) {
        return new KvRuntimeConfig(true, 100, 100, 1000, stores);
    }

    private RingloomRuntime runtime(String serviceName, RuntimeMode mode, KvRuntimeConfig kv) {
        String storage = broker.storagePath() != null
                ? broker.storagePath()
                : workspace.resolve("storage").toString();
        RingloomApplicationConfig config = new RingloomApplicationConfig(
                new RingloomServiceRuntimeConfig(
                        serviceName, storage, broker.group(), broker.nodeId(), false, 0, 0, 0, false),
                new RingloomRuntimeConfig(mode, null, null, null, null, null, false, null),
                null,
                Map.of(),
                null,
                kv);
        RingloomRuntime runtime = new RingloomRuntime(
                config,
                new EmptyApplication(serviceName),
                SerializerRegistry.EMPTY,
                UnavailableRingloomMetrics.INSTANCE,
                LoggerFactory.getLogger("kv-it." + serviceName));
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

    private MemorySegment longKey(long key) {
        MemorySegment segment = arena.allocate(8);
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN), 0, key ^ Long.MIN_VALUE);
        return segment;
    }

    private MemorySegment bytes(String text) {
        byte[] data = text.getBytes();
        MemorySegment segment = arena.allocate(Math.max(1, data.length));
        MemorySegment.copy(data, 0, segment, ValueLayout.JAVA_BYTE, 0, data.length);
        return segment;
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
