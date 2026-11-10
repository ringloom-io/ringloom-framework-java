// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import static org.assertj.core.api.Assertions.assertThat;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.TestBroker;
import io.ringloom.framework.config.MessageExecutionConfig;
import io.ringloom.framework.config.MessageExecutionMode;
import io.ringloom.framework.config.PartitionedExecutionConfig;
import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.config.RingloomRuntimeConfig;
import io.ringloom.framework.config.RingloomServiceRuntimeConfig;
import io.ringloom.framework.config.RuntimeMode;
import io.ringloom.framework.config.VirtualThreadExecutionConfig;
import io.ringloom.framework.config.WorkerBackpressurePolicy;
import io.ringloom.framework.config.kv.KvAcquireMode;
import io.ringloom.framework.config.kv.KvRuntimeConfig;
import io.ringloom.framework.config.kv.KvStoreConfigOverride;
import io.ringloom.framework.config.kv.KvStoreRuntimeConfig;
import io.ringloom.framework.config.kv.KvTtlClock;
import io.ringloom.framework.generated.GeneratedClientBinding;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.framework.generated.GeneratedPartitionKeyExtractor;
import io.ringloom.framework.generated.GeneratedRingloomApplication;
import io.ringloom.framework.metrics.UnavailableRingloomMetrics;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.framework.status.RingloomHandlerStatus;
import io.ringloom.framework.topic.ack.AckStatus;
import io.ringloom.service.RingloomClient;
import io.ringloom.service.RingloomStatus;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
 * Phase 16G raw-API integration scenarios (docs/impl/16-kv-stores.md §11.1 scenarios 5, 6 and 7)
 * against a single-node KV-enabled test broker. Scenarios 1–4 are in {@link KvRuntimeIntegrationTest},
 * scenario 8 in {@code DedicatedModeTopicAckIntegrationTest}; the generated-store variants live in
 * {@code samples/market-data-kv} (the processor cannot run on core test sources).
 */
@EnabledIfSystemProperty(named = "ringloom.brokerBin", matches = ".+")
final class KvRuntimeScenariosIntegrationTest {
    private static final Path REPO_ROOT =
            Path.of(System.getProperty("ringloom.repoRoot", "/home/dragan/code/ringloom"));
    private static final ValueLayout.OfLong BE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final int READ_TEMPLATE_ID = 4242;
    private static final String HANDLER_SERVICE = "kv-handler";

    private final List<RingloomRuntime> runtimes = new ArrayList<>();
    private TestBroker broker;
    private Path workspace;
    private Arena arena;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createTempDirectory("ringloom-kv-scenarios-");
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

    // ---- scenario 5 ----------------------------------------------------------------------------

    @Test
    void readsFromPartitionedWorkerHandlers() throws Exception {
        MessageExecutionConfig execution = new MessageExecutionConfig(
                MessageExecutionMode.PARTITIONED_WORKERS,
                new PartitionedExecutionConfig(2, 1024, 64, WorkerBackpressurePolicy.PARK_CONSUMER),
                null);
        HandlerResult result = runHandlerReads(execution, 200);
        assertThat(result.ok.get()).isEqualTo(200);
        assertThat(result.virtual.get()).isZero();
    }

    @Test
    void readsFromVirtualThreadHandlersAndManyConcurrentVirtualThreads() throws Exception {
        MessageExecutionConfig execution = new MessageExecutionConfig(
                MessageExecutionMode.VIRTUAL_THREADS, null, new VirtualThreadExecutionConfig(1024));
        HandlerResult result = runHandlerReads(execution, 200);
        assertThat(result.ok.get()).isEqualTo(200);
        assertThat(result.virtual.get()).isEqualTo(200);

        // 1000 concurrent virtual threads share a pool of 4 sessions.
        KvReaderStore reader = result.handlerRuntime.kv().reader("shared");
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger okReads = new AtomicInteger();
        AtomicInteger badReads = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 1000; t++) {
            threads.add(Thread.ofVirtual().start(() -> {
                MemorySegment key = Arena.ofAuto().allocate(8);
                key.set(BE_LONG, 0, 7L ^ Long.MIN_VALUE);
                MemorySegment destination = Arena.ofAuto().allocate(64);
                long[] length = new long[1];
                try {
                    go.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < 20; i++) {
                    if (reader.get(key, 0, 8, destination, 0, 64, length) == RingloomStatus.OK && length[0] == 5) {
                        okReads.incrementAndGet();
                    } else {
                        badReads.incrementAndGet();
                    }
                }
            }));
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(30));
        }
        assertThat(badReads.get()).isZero();
        assertThat(okReads.get()).isEqualTo(20_000);
        // Pooled sessions never exceed sessionPoolSize; the handler threads are virtual too.
        assertThat(reader.sessionCount()).isLessThanOrEqualTo(4 + 4);
    }

    @Test
    void readsFromApplicationPlatformThread() throws Exception {
        RingloomRuntime runtime = runtime(
                "kv-app-thread",
                RuntimeMode.EXTERNAL,
                MessageExecutionConfig.consumerThread(),
                fastConfig(Map.of("app", KvStoreRuntimeConfig.defaults().withRoles(true, true))),
                new TestApplication("kv-app-thread", null, null));
        runtime.start();
        KvWriterStore writer = runtime.kv().writer("app");
        KvReaderStore reader = runtime.kv().reader("app");
        MemorySegment key = longKey(1);
        MemorySegment value = bytes("hello");
        assertThat(writer.put(key, 0, 8, value, 0, 5, KvTtl.NEVER)).isEqualTo(RingloomStatus.OK);
        pollUntil(runtime, () -> reader.contains(key, 0, 8) == RingloomStatus.OK, 5000);

        CompletableFuture<Integer> read = new CompletableFuture<>();
        Thread.ofPlatform().name("app-reader").start(() -> {
            long[] length = new long[1];
            MemorySegment destination = Arena.ofAuto().allocate(64);
            int status = reader.get(key, 0, 8, destination, 0, 64, length);
            read.complete(status == RingloomStatus.OK && length[0] == 5 ? status : -status - 1);
        });
        assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo(RingloomStatus.OK);
    }

    // ---- scenario 6 ----------------------------------------------------------------------------

    @Test
    void batchOfThousandPutsWithCommitAckBlocksOtherThreads() throws Exception {
        RingloomRuntime runtime = runtime(
                "kv-batch",
                RuntimeMode.EXTERNAL,
                MessageExecutionConfig.consumerThread(),
                fastConfig(Map.of("batch", KvStoreRuntimeConfig.defaults().withRoles(true, true))),
                new TestApplication("kv-batch", null, null));
        runtime.start();
        KvWriterStore writer = runtime.kv().writer("batch");
        KvReaderStore reader = runtime.kv().reader("batch");
        MemorySegment value = bytes("v");
        MemorySegment otherKey = longKey(-1);

        assertThat(writer.beginBatch()).isEqualTo(RingloomStatus.OK);
        for (int i = 0; i < 1000; i++) {
            assertThat(writer.put(longKey(i), 0, 8, value, 0, 1, KvTtl.NEVER)).isEqualTo(RingloomStatus.OK);
        }
        CompletableFuture<Integer> otherPut = new CompletableFuture<>();
        Thread.ofPlatform()
                .name("other-writer")
                .start(() -> otherPut.complete(writer.put(otherKey, 0, 8, value, 0, 1, KvTtl.NEVER)));
        Thread.sleep(200);
        assertThat(otherPut)
                .as("put from another thread blocks while the batch is open")
                .isNotDone();

        AtomicReference<AckStatus> ack = new AtomicReference<>();
        assertThat(writer.commitBatchAck((seq, status, ctx) -> ack.set(status), null))
                .isEqualTo(RingloomStatus.OK);
        assertThat(otherPut.get(5, TimeUnit.SECONDS)).isEqualTo(RingloomStatus.OK);
        pollUntil(runtime, () -> ack.get() != null, 5000);
        assertThat(ack.get()).isEqualTo(AckStatus.ACKED);
        pollUntil(runtime, () -> reader.contains(longKey(999), 0, 8) == RingloomStatus.OK, 5000);
        pollUntil(runtime, () -> reader.contains(otherKey, 0, 8) == RingloomStatus.OK, 5000);
        AtomicInteger entries = new AtomicInteger();
        assertThat(reader.forEachRaw((ka, kl, va, vl) -> entries.incrementAndGet() > 0))
                .isEqualTo(RingloomStatus.OK);
        assertThat(entries.get()).isEqualTo(1001);
    }

    // ---- scenario 7 ----------------------------------------------------------------------------

    @Test
    void shutdownWithSessionsBoundOnDeadThreadsReleasesTheLease() throws Exception {
        RingloomRuntime active = runtime(
                "kv-dying",
                RuntimeMode.EXTERNAL,
                MessageExecutionConfig.consumerThread(),
                fastConfig(Map.of("dying", KvStoreRuntimeConfig.defaults().withRoles(true, true))),
                new TestApplication("kv-dying", null, null));
        active.start();
        KvWriterStore writer = active.kv().writer("dying");
        KvReaderStore reader = active.kv().reader("dying");
        MemorySegment key = longKey(5);
        MemorySegment value = bytes("x");
        assertThat(writer.put(key, 0, 8, value, 0, 1, KvTtl.NEVER)).isEqualTo(RingloomStatus.OK);
        pollUntil(active, () -> reader.contains(key, 0, 8) == RingloomStatus.OK, 5000);

        // Bind sessions on platform threads that then die.
        for (int i = 0; i < 8; i++) {
            Thread thread = Thread.ofPlatform().start(() -> reader.contains(key, 0, 8));
            thread.join();
        }
        assertThat(reader.sessionCount()).isGreaterThanOrEqualTo(8);

        RingloomRuntime standby = runtime(
                "kv-waiting",
                RuntimeMode.DEDICATED,
                MessageExecutionConfig.consumerThread(),
                fastConfig(Map.of("dying", waitWriter())),
                new TestApplication("kv-waiting", null, null));
        standby.start();
        KvWriterStore standbyWriter = standby.kv().writer("dying");
        assertThat(standbyWriter.state()).isEqualTo(KvWriterState.QUEUED);
        List<KvWriterStateEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        standby.kv().addWriterStateListener(events::add);
        standby.startEventLoops(Thread.ofPlatform().name("kv-waiting-", 0).factory());
        // While the active writer holds the lease (and keeps writing) the standby must stay QUEUED.
        for (int i = 0; i < 30; i++) {
            assertThat(writer.put(key, 0, 8, value, 0, 1, KvTtl.NEVER)).isEqualTo(RingloomStatus.OK);
            active.pollControl();
            Thread.sleep(100);
        }
        assertThat(events).noneSatisfy(event -> assertThat(event.state()).isEqualTo(KvWriterState.GRANTED));
        assertThat(standbyWriter.state()).isEqualTo(KvWriterState.QUEUED);

        active.close();
        runtimes.remove(active);
        assertThat(reader.state()).isEqualTo(KvStoreState.CLOSED);
        assertThat(reader.sessionCount()).as("every bound session closed").isZero();
        assertThat(reader.contains(key, 0, 8)).isEqualTo(RingloomHandlerStatus.SHUTDOWN);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && standbyWriter.state() != KvWriterState.GRANTED) {
            Thread.sleep(10);
        }
        assertThat(standbyWriter.state()).isEqualTo(KvWriterState.GRANTED);
        assertThat(standbyWriter.writerEpoch()).isPositive();
        assertThat(events).allSatisfy(event -> {
            if (event.state() == KvWriterState.GRANTED) {
                assertThat(event.writerEpoch()).isPositive();
            }
        });
        assertThat(standbyWriter.put(key, 0, 8, value, 0, 1, KvTtl.NEVER)).isEqualTo(RingloomStatus.OK);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private record HandlerResult(RingloomRuntime handlerRuntime, AtomicInteger ok, AtomicInteger virtual) {}

    /**
     * Starts a handler runtime (DEDICATED, given execution mode) that reads key 7 of store
     * {@code shared} on every inbound message, plus a sender runtime that writes the value and sends
     * {@code messages} messages to the handler service.
     */
    private HandlerResult runHandlerReads(MessageExecutionConfig execution, int messages) throws Exception {
        RingloomRuntime sender = runtime(
                "kv-sender",
                RuntimeMode.EXTERNAL,
                MessageExecutionConfig.consumerThread(),
                fastConfig(Map.of("shared", KvStoreRuntimeConfig.defaults().withRoles(false, true))),
                new TestApplication("kv-sender", HANDLER_SERVICE, null));
        sender.start();
        MemorySegment key = longKey(7);
        MemorySegment value = bytes("hello");
        AtomicReference<AckStatus> ack = new AtomicReference<>();
        assertThat(sender.kv()
                        .writer("shared")
                        .putAck(key, 0, 8, value, 0, 5, KvTtl.NEVER, (s, status, c) -> ack.set(status), null))
                .isEqualTo(RingloomStatus.OK);
        pollUntil(sender, () -> ack.get() != null, 5000);
        assertThat(ack.get()).isEqualTo(AckStatus.ACKED);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger virtual = new AtomicInteger();
        AtomicInteger received = new AtomicInteger();
        AtomicReference<KvReaderStore> readerRef = new AtomicReference<>();
        GeneratedMessageDispatcher dispatcher = (message, context) -> {
            KvReaderStore reader = readerRef.get();
            MemorySegment k = Arena.ofAuto().allocate(8);
            k.set(BE_LONG, 0, 7L ^ Long.MIN_VALUE);
            MemorySegment destination = Arena.ofAuto().allocate(64);
            long[] length = new long[1];
            if (reader != null && reader.get(k, 0, 8, destination, 0, 64, length) == RingloomStatus.OK) {
                ok.incrementAndGet();
            }
            if (Thread.currentThread().isVirtual()) {
                virtual.incrementAndGet();
            }
            received.incrementAndGet();
            return RingloomHandlerStatus.OK;
        };
        KvStoreRuntimeConfig readerSettings = new KvStoreRuntimeConfig(
                true,
                false,
                true,
                5000,
                false,
                KvTtlClock.PRECISE,
                4,
                4,
                KvAcquireMode.FAIL_IF_HELD,
                5000,
                0,
                16,
                0,
                KvStoreConfigOverride.none());
        RingloomRuntime handler = runtime(
                HANDLER_SERVICE,
                RuntimeMode.DEDICATED,
                execution,
                fastConfig(Map.of("shared", readerSettings)),
                new TestApplication(HANDLER_SERVICE, null, dispatcher));
        handler.start();
        KvReaderStore reader = handler.kv().reader("shared");
        pollUntil(handler, () -> reader.contains(key, 0, 8) == RingloomStatus.OK, 5000);
        readerRef.set(reader);
        handler.startEventLoops(Thread.ofPlatform().name("kv-handler-", 0).factory());

        RingloomClient client = sender.generatedClient(RingloomClient.class);
        MemorySegment payload = arena.allocate(8);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        int sent = 0;
        while (sent < messages && System.nanoTime() < deadline) {
            payload.set(ValueLayout.JAVA_LONG, 0, sent);
            if (client.sendMessage(READ_TEMPLATE_ID, payload) == RingloomStatus.OK) {
                sent++;
            } else {
                sender.pollControl();
                Thread.sleep(5);
            }
        }
        assertThat(sent).isEqualTo(messages);
        while (received.get() < messages && System.nanoTime() < deadline) {
            sender.pollControl();
            Thread.sleep(5);
        }
        assertThat(received.get()).isEqualTo(messages);
        return new HandlerResult(handler, ok, virtual);
    }

    private static KvStoreRuntimeConfig waitWriter() {
        return new KvStoreRuntimeConfig(
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
    }

    private static KvRuntimeConfig fastConfig(Map<String, KvStoreRuntimeConfig> stores) {
        return new KvRuntimeConfig(true, 100, 100, 1000, stores);
    }

    private RingloomRuntime runtime(
            String serviceName,
            RuntimeMode mode,
            MessageExecutionConfig execution,
            KvRuntimeConfig kv,
            GeneratedRingloomApplication application) {
        String storage = broker.storagePath() != null
                ? broker.storagePath()
                : workspace.resolve("storage").toString();
        RingloomApplicationConfig config = new RingloomApplicationConfig(
                new RingloomServiceRuntimeConfig(
                        serviceName, storage, broker.group(), broker.nodeId(), false, 0, 0, 0, false),
                new RingloomRuntimeConfig(mode, null, null, execution, null, null, false, null),
                null,
                Map.of(),
                null,
                kv);
        RingloomRuntime runtime = new RingloomRuntime(
                config,
                application,
                SerializerRegistry.EMPTY,
                UnavailableRingloomMetrics.INSTANCE,
                LoggerFactory.getLogger("kv-scenarios." + serviceName));
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
        segment.set(BE_LONG, 0, key ^ Long.MIN_VALUE);
        return segment;
    }

    private MemorySegment bytes(String text) {
        byte[] data = text.getBytes();
        MemorySegment segment = arena.allocate(Math.max(1, data.length));
        MemorySegment.copy(data, 0, segment, ValueLayout.JAVA_BYTE, 0, data.length);
        return segment;
    }

    /** Hand-written application: optional raw client to {@code target}, optional dispatcher. */
    private static final class TestApplication implements GeneratedRingloomApplication {
        private final String serviceName;
        private final String target;
        private final GeneratedMessageDispatcher dispatcher;

        TestApplication(String serviceName, String target, GeneratedMessageDispatcher dispatcher) {
            this.serviceName = serviceName;
            this.target = target;
            this.dispatcher = dispatcher;
        }

        @Override
        public String serviceName() {
            return serviceName;
        }

        @Override
        public List<GeneratedClientBinding<?>> clients() {
            if (target == null) {
                return List.of();
            }
            return List.of(new GeneratedClientBinding<RingloomClient>() {
                @Override
                public Class<RingloomClient> clientType() {
                    return RingloomClient.class;
                }

                @Override
                public String targetServiceName() {
                    return target;
                }

                @Override
                public RingloomClient create(
                        RingloomRuntime runtime, RingloomClient lowLevelClient, SerializerRegistry serializers) {
                    return lowLevelClient;
                }
            });
        }

        @Override
        public Map<Integer, GeneratedPartitionKeyExtractor> partitionKeyExtractors() {
            return Map.of(
                    READ_TEMPLATE_ID,
                    (message, context) -> message.payloadSegment().get(ValueLayout.JAVA_LONG_UNALIGNED, 0));
        }

        @Override
        public GeneratedMessageDispatcher dispatcher() {
            return dispatcher != null ? dispatcher : (message, context) -> RingloomHandlerStatus.OK;
        }
    }
}
