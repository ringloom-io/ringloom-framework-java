// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.marketdatakv.writer;

import io.avaje.inject.Component;
import io.avaje.inject.External;
import io.ringloom.framework.RingloomApplicationRunner;
import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomKvPut;
import io.ringloom.framework.annotation.RingloomKvStore;
import io.ringloom.framework.annotation.RingloomKvWriterStateHandler;
import io.ringloom.framework.annotation.RingloomSchedule;
import io.ringloom.framework.ioc.avaje.AvajeRingloomBootstrap;
import io.ringloom.framework.kv.KvWriterOperations;
import io.ringloom.framework.kv.KvWriterState;
import io.ringloom.framework.kv.KvWriterStateEvent;
import io.ringloom.samples.marketdatakv.common.Instruments;
import io.ringloom.samples.marketdatakv.common.ServiceNames;
import io.ringloom.samples.marketdatakv.sbe.PriceSnapshotDto;
import io.ringloom.service.RingloomStatus;
import jakarta.inject.Inject;
import java.nio.file.Path;

/**
 * Holds the {@code prices} writer lease and republishes every instrument's price twice a second.
 * Run two instances: the second queues for the lease ({@code acquire: wait}) and takes over when
 * the active writer stops.
 */
@RingloomApplication(service = ServiceNames.PRICE_WRITER)
public final class PriceWriterApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/market-data-kv/config/price-writer.yaml");

    public static void main(String[] args) throws Exception {
        Path config = args.length > 0 ? Path.of(args[0]) : DEFAULT_CONFIG;
        try (RingloomApplicationRunner runner =
                AvajeRingloomBootstrap.fromYaml(config).start()) {
            System.out.printf(
                    "%s ready (writer lease: %s); press Ctrl+C to stop%n",
                    ServiceNames.PRICE_WRITER,
                    runner.runtime().kv().writer(ServiceNames.PRICES_STORE).state());
            runner.awaitShutdown();
        }
    }
}

/** Write side of the {@code prices} store: {@code long} instrument id to an SBE {@code PriceSnapshot}. */
@RingloomKvStore(name = ServiceNames.PRICES_STORE, serializer = "sbe", maxKeyLength = 8, maxValueLength = 64)
interface PriceStoreWriter extends KvWriterOperations {
    @RingloomKvPut
    int put(long instrumentId, PriceSnapshotDto snapshot);
}

@Component
final class PricePublisher {

    private final PriceStoreWriter prices;
    private final PriceSnapshotDto snapshot = new PriceSnapshotDto();
    private final long[] mids = Instruments.MID_NANOS.clone();
    private long updateId;
    private long rejected;

    @Inject
    PricePublisher(@External PriceStoreWriter prices) {
        this.prices = prices;
    }

    /** Runs on the control thread; publishes one batch with every instrument while the lease is held. */
    @RingloomSchedule(initialDelayMillis = 500, fixedRateMillis = 500)
    public void publishPrices() {
        if (prices.writerState() != KvWriterState.GRANTED) {
            return;
        }
        if (prices.beginBatch() != RingloomStatus.OK) {
            return;
        }
        long epoch = prices.writerEpoch();
        for (int i = 0; i < Instruments.COUNT; i++) {
            long mid = mids[i] + ((updateId + i) % 7 - 3) * 10_000_000L;
            mids[i] = mid;
            long spread = Math.max(50_000L, mid / 100_000L);
            snapshot.instrumentId(Instruments.IDS[i]);
            snapshot.bidNanos(mid - spread);
            snapshot.askNanos(mid + spread);
            snapshot.updateId(++updateId);
            snapshot.writerEpoch(epoch);
            prices.put(Instruments.IDS[i], snapshot);
        }
        int status = prices.commitBatch();
        if (status != RingloomStatus.OK) {
            // The batch stays buffered on failure; drop it and try again on the next tick.
            prices.abandonBatch();
            if (++rejected % 10 == 1) {
                System.out.printf("price batch rejected status=%d (total %d)%n", status, rejected);
            }
        }
    }

    /** Runs on the control thread after native polling, never inside a native callback. */
    @RingloomKvWriterStateHandler(store = ServiceNames.PRICES_STORE)
    public void onWriterState(KvWriterStateEvent event) {
        System.out.printf(
                "writer lease for '%s': %s -> %s (epoch %d)%n",
                event.store(), event.previous(), event.state(), event.writerEpoch());
    }
}
