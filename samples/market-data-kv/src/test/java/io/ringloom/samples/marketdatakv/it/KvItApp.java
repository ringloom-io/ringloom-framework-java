// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.marketdatakv.it;

import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomKvClear;
import io.ringloom.framework.annotation.RingloomKvContains;
import io.ringloom.framework.annotation.RingloomKvDelete;
import io.ringloom.framework.annotation.RingloomKvForEach;
import io.ringloom.framework.annotation.RingloomKvGet;
import io.ringloom.framework.annotation.RingloomKvPut;
import io.ringloom.framework.annotation.RingloomKvStore;
import io.ringloom.framework.annotation.RingloomKvTtl;
import io.ringloom.framework.annotation.RingloomKvWriterStateHandler;
import io.ringloom.framework.annotation.RingloomServiceComponent;
import io.ringloom.framework.kv.KvEntryVisitor;
import io.ringloom.framework.kv.KvReaderOperations;
import io.ringloom.framework.kv.KvValue;
import io.ringloom.framework.kv.KvWriterOperations;
import io.ringloom.framework.kv.KvWriterStateEvent;
import io.ringloom.framework.topic.ack.AckCallback;
import io.ringloom.samples.marketdatakv.sbe.PriceSnapshotDecoder;
import io.ringloom.samples.marketdatakv.sbe.PriceSnapshotDto;
import io.ringloom.service.KvRawVisitor;
import io.ringloom.service.TopicAckMode;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test-only application whose generated KV stores are exercised by the 16G integration tests. */
@RingloomApplication(service = KvItApp.SERVICE)
public final class KvItApp {
    public static final String SERVICE = "kv-it";
    public static final String PRICES = "it-prices";
    public static final String SYMBOLS = "it-symbols";

    private KvItApp() {}
}

/** {@code long} keys, SBE values; read and write methods in one interface. */
@RingloomKvStore(name = KvItApp.PRICES, serializer = "sbe", maxKeyLength = 8, maxValueLength = 64)
interface ItPriceStore extends KvReaderOperations, KvWriterOperations {
    @RingloomKvGet
    PriceSnapshotDecoder get(long instrumentId);

    @RingloomKvGet
    int get(long instrumentId, KvValue<PriceSnapshotDecoder> out);

    @RingloomKvContains
    boolean contains(long instrumentId);

    @RingloomKvForEach
    int forEach(KvEntryVisitor<PriceSnapshotDecoder> visitor);

    @RingloomKvForEach
    int forEachRaw(KvRawVisitor visitor);

    @RingloomKvPut
    int put(long instrumentId, PriceSnapshotDto snapshot);

    @RingloomKvPut
    int putExpiring(long instrumentId, PriceSnapshotDto snapshot, @RingloomKvTtl long ttlMillis);

    @RingloomKvPut(ackMode = TopicAckMode.REPLICATE_ONCE)
    int putAcked(long instrumentId, PriceSnapshotDto snapshot, AckCallback callback, Object context);

    @RingloomKvDelete
    int delete(long instrumentId);

    @RingloomKvClear
    int clear();
}

/** {@code String} keys (UTF-8, encoded without allocation). */
@RingloomKvStore(name = KvItApp.SYMBOLS, serializer = "sbe", maxKeyLength = 32, maxValueLength = 64)
interface ItSymbolStore extends KvReaderOperations, KvWriterOperations {
    @RingloomKvGet
    int get(String symbol, KvValue<PriceSnapshotDecoder> out);

    @RingloomKvPut
    int put(String symbol, PriceSnapshotDto snapshot);
}

/** Records generated writer-state handler calls (supplied per runtime through {@code withComponents}). */
@RingloomServiceComponent
final class ItWriterStates {
    final List<KvWriterStateEvent> events = new CopyOnWriteArrayList<>();

    @RingloomKvWriterStateHandler(store = KvItApp.PRICES)
    public void onPricesWriterState(KvWriterStateEvent event) {
        events.add(event);
    }
}
