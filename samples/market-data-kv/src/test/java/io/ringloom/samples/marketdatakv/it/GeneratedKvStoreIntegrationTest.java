// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.marketdatakv.it;

import static io.ringloom.samples.marketdatakv.it.KvItRuntimes.pollUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.config.RuntimeMode;
import io.ringloom.framework.config.kv.KvAcquireMode;
import io.ringloom.framework.kv.KvValue;
import io.ringloom.framework.kv.KvWriterState;
import io.ringloom.framework.kv.KvWriterStateEvent;
import io.ringloom.framework.kv.RingloomKvException;
import io.ringloom.framework.kv.codec.KvKeyCodecs;
import io.ringloom.framework.status.RingloomHandlerStatus;
import io.ringloom.framework.topic.ack.AckStatus;
import io.ringloom.samples.marketdatakv.sbe.PriceSnapshotDecoder;
import io.ringloom.samples.marketdatakv.sbe.PriceSnapshotDto;
import io.ringloom.service.RingloomStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Phase 16G integration tests through generated {@code @RingloomKvStore} proxies with SBE values
 * (docs/impl/16-kv-stores.md §11.1 scenario 1, a cross-runtime read, and the generated variant of
 * scenario 4 with a generated {@code @RingloomKvWriterStateHandler}).
 * Raw-API scenarios are in {@code ringloom-framework-core} ({@code KvRuntimeIntegrationTest},
 * {@code KvRuntimeScenariosIntegrationTest}).
 */
@EnabledIfSystemProperty(named = "ringloom.brokerBin", matches = ".+")
final class GeneratedKvStoreIntegrationTest {
    private final List<RingloomRuntime> runtimes = new ArrayList<>();
    private KvTestBroker broker;
    private Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createTempDirectory("ringloom-kv-generated-it-");
        broker = KvTestBroker.startKvEnabled(KvItRuntimes.REPO_ROOT, workspace);
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
    }

    @Test
    void generatedWriterAndReaderInOneRuntime() throws Exception {
        ItWriterStates states = new ItWriterStates();
        RingloomRuntime runtime = start("kv-it-rw", RuntimeMode.EXTERNAL, KvAcquireMode.FAIL_IF_HELD, states);
        ItPriceStore prices = runtime.generatedClient(ItPriceStore.class);
        ItSymbolStore symbols = runtime.generatedClient(ItSymbolStore.class);
        assertThat(prices.storeName()).isEqualTo(KvItApp.PRICES);
        assertThat(prices.writerState()).isEqualTo(KvWriterState.GRANTED);
        assertThat(prices.writerEpoch()).isPositive();
        pollUntil(runtime, () -> !states.events.isEmpty(), 2000);
        assertThat(states.events.getFirst().state()).isEqualTo(KvWriterState.GRANTED);

        PriceSnapshotDto snapshot = snapshot(101, 1_000);
        assertThat(prices.put(101, snapshot)).isEqualTo(RingloomStatus.OK);
        AtomicReference<AckStatus> ack = new AtomicReference<>();
        assertThat(prices.putAcked(102, snapshot(102, 2_000), (seq, status, ctx) -> ack.set(status), null))
                .isEqualTo(RingloomStatus.OK);
        pollUntil(runtime, () -> ack.get() != null, 5000);
        assertThat(ack.get()).isEqualTo(AckStatus.ACKED);
        pollUntil(runtime, () -> prices.contains(102), 5000);
        pollUntil(runtime, prices::isReady, 5000);

        PriceSnapshotDecoder flyweight = prices.get(101);
        assertThat(flyweight).isNotNull();
        assertThat(flyweight.instrumentId()).isEqualTo(101);
        assertThat(flyweight.bidNanos()).isEqualTo(1_000);
        assertThat(flyweight.askNanos()).isEqualTo(1_010);
        KvValue<PriceSnapshotDecoder> holder = new KvValue<>();
        assertThat(prices.get(102, holder)).isEqualTo(RingloomStatus.OK);
        assertThat(holder.value().bidNanos()).isEqualTo(2_000);
        assertThat(holder.length()).isPositive();
        assertThat(prices.get(999)).isNull();
        assertThat(prices.get(999, holder)).isEqualTo(RingloomStatus.KV_MISSING);
        assertThat(holder.value()).isNull();
        assertThat(prices.contains(999)).isFalse();

        // String keys.
        assertThat(symbols.put("AAPL", snapshot(101, 3_000))).isEqualTo(RingloomStatus.OK);
        pollUntil(runtime, () -> symbols.get("AAPL", holder) == RingloomStatus.OK, 5000);
        assertThat(holder.value().bidNanos()).isEqualTo(3_000);
        assertThat(symbols.get("\uD800", holder)).isEqualTo(RingloomStatus.INVALID_ARGUMENT);
        assertThat(symbols.get("x".repeat(40), holder)).isEqualTo(RingloomStatus.KV_KEY_TOO_LARGE);

        // forEach decodes values; keys are raw bytes decoded with KvKeyCodecs.
        List<Long> keys = new ArrayList<>();
        List<Long> bids = new ArrayList<>();
        assertThat(prices.forEach((key, value) -> {
                    keys.add(KvKeyCodecs.decodeLong(key));
                    bids.add(value.bidNanos());
                    return true;
                }))
                .isEqualTo(RingloomStatus.OK);
        assertThat(keys).containsExactly(101L, 102L);
        assertThat(bids).containsExactly(1_000L, 2_000L);
        AtomicInteger raw = new AtomicInteger();
        assertThat(prices.forEachRaw((ka, kl, va, vl) -> raw.incrementAndGet() > 0))
                .isEqualTo(RingloomStatus.OK);
        assertThat(raw.get()).isEqualTo(2);

        // TTL: put with 200 ms, gone after 300 ms.
        assertThat(prices.putExpiring(103, snapshot(103, 4_000), 200)).isEqualTo(RingloomStatus.OK);
        pollUntil(runtime, () -> prices.contains(103), 5000);
        Thread.sleep(300);
        assertThat(prices.contains(103)).isFalse();
        assertThat(prices.get(103)).isNull();

        assertThat(prices.delete(101)).isEqualTo(RingloomStatus.OK);
        pollUntil(runtime, () -> !prices.contains(101), 5000);
        assertThat(prices.get(101)).isNull();
        assertThat(prices.contains(102)).isTrue();

        assertThat(prices.clear()).isEqualTo(RingloomStatus.OK);
        pollUntil(runtime, () -> !prices.contains(102), 5000);
        assertThat(prices.status().readerState()).isNotNull();

        // Batches through the generated KvWriterOperations.
        assertThat(prices.beginBatch()).isEqualTo(RingloomStatus.OK);
        for (int i = 0; i < 100; i++) {
            assertThat(prices.put(1_000 + i, snapshot(1_000 + i, i))).isEqualTo(RingloomStatus.OK);
        }
        AtomicReference<AckStatus> batchAck = new AtomicReference<>();
        assertThat(prices.commitBatchAck((seq, status, ctx) -> batchAck.set(status), null))
                .isEqualTo(RingloomStatus.OK);
        pollUntil(runtime, () -> batchAck.get() != null, 5000);
        assertThat(batchAck.get()).isEqualTo(AckStatus.ACKED);
        pollUntil(runtime, () -> prices.contains(1_099), 5000);

        runtime.close();
        assertThat(prices.get(1_099)).isNull();
        assertThat(prices.get(1_099, holder)).isEqualTo(RingloomHandlerStatus.SHUTDOWN);
        assertThat(prices.put(1, snapshot)).isEqualTo(RingloomHandlerStatus.SHUTDOWN);
        assertThat(prices.writerState()).isEqualTo(KvWriterState.CLOSED);
    }

    @Test
    void secondRuntimeReadsThroughGeneratedProxyWhileQueuedAsWriter() throws Exception {
        // KvItApp's stores read and write, so the second runtime also opens writers; with WAIT it
        // queues for the leases and its writes are rejected while it reads the first runtime's data.
        // (A reader started before any writer is covered by the raw-API core test and the sample.)
        RingloomRuntime writerRuntime = start("kv-it-writer", RuntimeMode.EXTERNAL, KvAcquireMode.FAIL_IF_HELD, null);
        ItPriceStore writer = writerRuntime.generatedClient(ItPriceStore.class);
        assertThat(writer.put(7, snapshot(7, 70))).isEqualTo(RingloomStatus.OK);

        RingloomRuntime readerRuntime = start("kv-it-reader", RuntimeMode.EXTERNAL, KvAcquireMode.WAIT, null);
        ItPriceStore reader = readerRuntime.generatedClient(ItPriceStore.class);
        assertThat(reader.writerState()).isEqualTo(KvWriterState.QUEUED);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && reader.get(7) == null) {
            writerRuntime.pollControl();
            readerRuntime.pollControl();
            Thread.sleep(10);
        }
        assertThat(reader.get(7)).isNotNull();
        assertThat(reader.get(7).bidNanos()).isEqualTo(70);
        assertThat(reader.put(8, snapshot(8, 80))).isEqualTo(RingloomStatus.KV_NOT_WRITER);
    }

    @Test
    void generatedWriterStateHandlerSeesStandbyTakeover() throws Exception {
        RingloomRuntime active = start("kv-it-active", RuntimeMode.EXTERNAL, KvAcquireMode.FAIL_IF_HELD, null);
        ItPriceStore activeStore = active.generatedClient(ItPriceStore.class);
        assertThat(activeStore.writerState()).isEqualTo(KvWriterState.GRANTED);

        RingloomRuntime rejected = KvItRuntimes.runtime(
                broker, workspace, "kv-it-rejected", RuntimeMode.EXTERNAL, KvAcquireMode.FAIL_IF_HELD, null);
        assertThatThrownBy(rejected::start)
                .isInstanceOf(RingloomKvException.class)
                .satisfies(ex ->
                        assertThat(((RingloomKvException) ex).statusCode()).isEqualTo(RingloomStatus.KV_HELD_BY_OTHER));
        rejected.close();

        ItWriterStates states = new ItWriterStates();
        RingloomRuntime standby = start("kv-it-standby", RuntimeMode.DEDICATED, KvAcquireMode.WAIT, states);
        ItPriceStore standbyStore = standby.generatedClient(ItPriceStore.class);
        assertThat(standbyStore.writerState()).isEqualTo(KvWriterState.QUEUED);
        standby.startEventLoops(Thread.ofPlatform().name("kv-it-standby-", 0).factory());
        assertThat(standbyStore.put(1, snapshot(1, 1))).isEqualTo(RingloomStatus.KV_NOT_WRITER);

        active.close();
        runtimes.remove(active);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && standbyStore.writerState() != KvWriterState.GRANTED) {
            Thread.sleep(10);
        }
        assertThat(standbyStore.writerState()).isEqualTo(KvWriterState.GRANTED);
        assertThat(states.events)
                .extracting(KvWriterStateEvent::state)
                .containsSubsequence(KvWriterState.QUEUED, KvWriterState.GRANTED);
        assertThat(states.events.getLast().writerEpoch()).isPositive();
        assertThat(standbyStore.put(1, snapshot(1, 1))).isEqualTo(RingloomStatus.OK);
    }

    private RingloomRuntime start(String serviceName, RuntimeMode mode, KvAcquireMode acquire, ItWriterStates states) {
        RingloomRuntime runtime = KvItRuntimes.runtime(broker, workspace, serviceName, mode, acquire, states);
        runtimes.add(runtime);
        runtime.start();
        return runtime;
    }

    static PriceSnapshotDto snapshot(long instrumentId, long bid) {
        PriceSnapshotDto dto = new PriceSnapshotDto();
        dto.instrumentId(instrumentId);
        dto.bidNanos(bid);
        dto.askNanos(bid + 10);
        dto.updateId(1);
        dto.writerEpoch(1);
        return dto;
    }
}
