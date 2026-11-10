// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.marketdatakv.it;

import static io.ringloom.samples.marketdatakv.it.KvItRuntimes.pollUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.config.RuntimeMode;
import io.ringloom.framework.config.kv.KvAcquireMode;
import io.ringloom.framework.kv.KvValue;
import io.ringloom.framework.topic.ack.AckCallback;
import io.ringloom.framework.topic.ack.AckStatus;
import io.ringloom.samples.marketdatakv.sbe.PriceSnapshotDecoder;
import io.ringloom.samples.marketdatakv.sbe.PriceSnapshotDto;
import io.ringloom.service.KvRawVisitor;
import io.ringloom.service.RingloomStatus;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Phase 16G allocation tests (docs/impl/16-kv-stores.md §11.2): steady-state generated store calls
 * allocate 0 bytes on a platform thread (the JUnit worker), measured with
 * {@code com.sun.management.ThreadMXBean#getThreadAllocatedBytes} after warm-up.
 */
@EnabledIfSystemProperty(named = "ringloom.brokerBin", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class GeneratedKvStoreAllocationTest {
    private static final int WARMUP_ITERATIONS = 50_000;
    private static final int MEASURED_ITERATIONS = 100_000;
    /** forEachRaw visits 1000 entries per call, so fewer calls give the same coverage. */
    private static final int FOR_EACH_WARMUP_ITERATIONS = 5_000;

    private static final int FOR_EACH_MEASURED_ITERATIONS = 10_000;
    private static final int ATTEMPTS = 3;
    private static final int KEYS = 64;
    private static final int FOR_EACH_ENTRIES = 1000;
    private static final int ACK_WINDOW = 256;

    private KvTestBroker broker;
    private RingloomRuntime runtime;
    private ItPriceStore prices;
    private ItSymbolStore symbols;
    private final PriceSnapshotDto snapshot = new PriceSnapshotDto();
    private final KvValue<PriceSnapshotDecoder> holder = new KvValue<>();
    private final String[] symbolKeys = new String[KEYS];
    private long sink;
    private long polls;
    private long pollBaselineBytes;
    private long acksIssued;
    private long acksCompleted;
    private long acksNotAcked;
    private final AckCallback ackCallback = this::onAck;
    private final KvRawVisitor rawVisitor = this::visitRaw;

    @BeforeAll
    void startRuntime() throws Exception {
        Path workspace = Files.createTempDirectory("ringloom-kv-alloc-");
        broker = KvTestBroker.startKvEnabled(KvItRuntimes.REPO_ROOT, workspace);
        runtime = KvItRuntimes.runtime(
                broker, workspace, "kv-it-alloc", RuntimeMode.EXTERNAL, KvAcquireMode.FAIL_IF_HELD, null);
        runtime.start();
        prices = runtime.generatedClient(ItPriceStore.class);
        symbols = runtime.generatedClient(ItSymbolStore.class);
        for (int i = 0; i < KEYS; i++) {
            symbolKeys[i] = "SYM-" + i;
            assertThat(prices.put(i, snapshot(i))).isEqualTo(RingloomStatus.OK);
            assertThat(symbols.put(symbolKeys[i], snapshot(i))).isEqualTo(RingloomStatus.OK);
        }
        pollUntil(runtime, () -> prices.contains(KEYS - 1), 5000);
        pollUntil(runtime, () -> symbols.get(symbolKeys[KEYS - 1], holder) == RingloomStatus.OK, 5000);
        pollBaselineBytes = measurePollBaseline();
    }

    /** Bytes allocated by one idle {@code pollControl()} (binding overhead; 0 once the binding is fixed). */
    private long measurePollBaseline() {
        com.sun.management.ThreadMXBean threads = threadMxBean();
        long threadId = Thread.currentThread().threadId();
        int calls = 20_000;
        for (int i = 0; i < calls; i++) {
            runtime.pollControl();
        }
        long before = threads.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < calls; i++) {
            runtime.pollControl();
        }
        long total = threads.getThreadAllocatedBytes(threadId) - before - measurementOverhead(threads, threadId);
        long perCall = total / calls;
        assertThat(total).as("pollControl allocation is a fixed per-call cost").isEqualTo(perCall * calls);
        return perCall;
    }

    @AfterAll
    void stopRuntime() throws Exception {
        if (runtime != null) {
            runtime.close();
        }
        if (broker != null) {
            broker.close();
        }
    }

    @Test
    void generatedGetReturningSbeFlyweightWithLongKeyDoesNotAllocate() {
        assertNoAllocation("get(long) flyweight", WARMUP_ITERATIONS, MEASURED_ITERATIONS, i -> {
            PriceSnapshotDecoder decoder = prices.get(i & (KEYS - 1));
            sink += decoder.bidNanos();
        });
    }

    @Test
    void generatedGetIntoHolderWithStringKeyDoesNotAllocate() {
        assertNoAllocation("get(String, KvValue)", WARMUP_ITERATIONS, MEASURED_ITERATIONS, i -> {
            int status = symbols.get(symbolKeys[i & (KEYS - 1)], holder);
            sink += status == RingloomStatus.OK ? holder.value().askNanos() : -1_000_000;
        });
    }

    @Test
    void generatedContainsDoesNotAllocate() {
        assertNoAllocation("contains(long)", WARMUP_ITERATIONS, MEASURED_ITERATIONS, i -> {
            sink += prices.contains(i & (KEYS - 1)) ? 1 : -1_000_000;
        });
    }

    @Test
    void generatedPutWithSbeEncoderAndLongKeyDoesNotAllocate() {
        assertNoAllocation("put(long, dto)", WARMUP_ITERATIONS, MEASURED_ITERATIONS, i -> {
            snapshot.instrumentId(i & (KEYS - 1));
            snapshot.bidNanos(i);
            snapshot.askNanos(i + 10L);
            int status;
            while ((status = prices.put(i & (KEYS - 1), snapshot)) == RingloomStatus.BACKPRESSURE) {
                poll();
            }
            sink += status == RingloomStatus.OK ? 1 : -1_000_000;
        });
    }

    @Test
    void generatedPutAckAndAckCompletionDoNotAllocate() {
        assertNoAllocation("putAcked(long, dto) + completion", WARMUP_ITERATIONS, MEASURED_ITERATIONS, i -> {
            snapshot.instrumentId(i & (KEYS - 1));
            snapshot.bidNanos(i);
            int status;
            while ((status = prices.putAcked(i & (KEYS - 1), snapshot, ackCallback, null))
                    == RingloomStatus.BACKPRESSURE) {
                poll();
            }
            if (status == RingloomStatus.OK) {
                acksIssued++;
            }
            sink += status == RingloomStatus.OK ? 1 : -1_000_000;
            if ((i & (ACK_WINDOW - 1)) == ACK_WINDOW - 1) {
                drainAcks();
            }
        });
        drainAcks();
        assertThat(acksNotAcked).isZero();
        assertThat(acksCompleted).isEqualTo(acksIssued);
    }

    @Test
    void forEachRawOverThousandEntriesDoesNotAllocate() throws Exception {
        for (int i = 0; i < FOR_EACH_ENTRIES; i++) {
            int status;
            while ((status = prices.put(10_000 + i, snapshot(i))) == RingloomStatus.BACKPRESSURE) {
                poll();
            }
            assertThat(status).isEqualTo(RingloomStatus.OK);
        }
        pollUntil(runtime, () -> prices.contains(10_000 + FOR_EACH_ENTRIES - 1), 5000);
        long before = sink;
        assertThat(prices.forEachRaw(rawVisitor)).isEqualTo(RingloomStatus.OK);
        assertThat(sink - before).isGreaterThanOrEqualTo(FOR_EACH_ENTRIES);

        assertNoAllocation(
                "forEachRaw over >= 1000 entries", FOR_EACH_WARMUP_ITERATIONS, FOR_EACH_MEASURED_ITERATIONS, i -> {
                    sink += prices.forEachRaw(rawVisitor);
                });
    }

    private void drainAcks() {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (acksCompleted < acksIssued && System.nanoTime() < deadline) {
            poll();
        }
        assertThat(acksCompleted).as("acks completed").isEqualTo(acksIssued);
    }

    /**
     * {@code RingloomRuntime.pollControl()}, counted. The bindings' {@code RingloomService.pollControl}
     * (0.5.0) allocates a confined arena per call; that fixed per-call cost is measured once
     * ({@link #pollBaselineBytes}) and subtracted, so the assertions cover the framework's own work
     * (KV ack registration and {@code afterControlPoll} completion). See docs/impl/16-kv-stores.md §15.
     */
    private void poll() {
        polls++;
        runtime.pollControl();
    }

    private void onAck(long sequence, AckStatus status, Object context) {
        acksCompleted++;
        if (status != AckStatus.ACKED) {
            acksNotAcked++;
        }
    }

    private boolean visitRaw(long keyAddress, long keyLength, long valueAddress, long valueLength) {
        sink += 1;
        return true;
    }

    private void assertNoAllocation(String operation, int warmup, int measured, IntConsumer body) {
        com.sun.management.ThreadMXBean threads = threadMxBean();
        long threadId = Thread.currentThread().threadId();
        assertThat(Thread.currentThread().isVirtual()).isFalse();
        run(body, warmup);
        long allocated = Long.MAX_VALUE;
        for (int attempt = 0; attempt < ATTEMPTS && allocated > 0; attempt++) {
            long pollsBefore = polls;
            long before = threads.getThreadAllocatedBytes(threadId);
            run(body, measured);
            long after = threads.getThreadAllocatedBytes(threadId);
            allocated =
                    after - before - measurementOverhead(threads, threadId) - (polls - pollsBefore) * pollBaselineBytes;
        }
        assertThat(allocated)
                .as("bytes allocated by %d x %s", measured, operation)
                .isLessThanOrEqualTo(0L);
        assertThat(sink).isNotZero();
    }

    private static void run(IntConsumer body, int iterations) {
        for (int i = 0; i < iterations; i++) {
            body.accept(i);
        }
    }

    private static PriceSnapshotDto snapshot(long instrumentId) {
        PriceSnapshotDto dto = new PriceSnapshotDto();
        dto.instrumentId(instrumentId);
        dto.bidNanos(1_000 + instrumentId);
        dto.askNanos(1_010 + instrumentId);
        dto.updateId(1);
        dto.writerEpoch(1);
        return dto;
    }

    private static long measurementOverhead(com.sun.management.ThreadMXBean threads, long threadId) {
        long first = threads.getThreadAllocatedBytes(threadId);
        long second = threads.getThreadAllocatedBytes(threadId);
        return second - first;
    }

    private static com.sun.management.ThreadMXBean threadMxBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean, "thread allocation counters unavailable");
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(threads.isThreadAllocatedMemorySupported(), "thread allocation counters unsupported");
        if (!threads.isThreadAllocatedMemoryEnabled()) {
            threads.setThreadAllocatedMemoryEnabled(true);
        }
        return threads;
    }
}
