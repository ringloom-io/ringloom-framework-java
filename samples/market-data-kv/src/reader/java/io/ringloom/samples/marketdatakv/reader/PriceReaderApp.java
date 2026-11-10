// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.marketdatakv.reader;

import io.avaje.inject.Component;
import io.avaje.inject.External;
import io.ringloom.framework.RingloomApplicationRunner;
import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.annotation.RingloomKvGet;
import io.ringloom.framework.annotation.RingloomKvStore;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.annotation.RoutingMode;
import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.framework.ioc.avaje.AvajeRingloomBootstrap;
import io.ringloom.framework.kv.KvReaderOperations;
import io.ringloom.samples.marketdatakv.common.ServiceNames;
import io.ringloom.samples.marketdatakv.sbe.PriceRequestDecoder;
import io.ringloom.samples.marketdatakv.sbe.PriceResponseDto;
import io.ringloom.samples.marketdatakv.sbe.PriceResponseEncoder;
import io.ringloom.samples.marketdatakv.sbe.PriceSnapshotDecoder;
import io.ringloom.service.RingloomStatus;
import jakarta.inject.Inject;
import java.nio.file.Path;

/**
 * Serves price lookups from the local replica of the {@code prices} KV store. Reads go to shared
 * memory (no broker round trip) and return a reusable SBE flyweight.
 */
@RingloomApplication(service = ServiceNames.PRICE_READER)
public final class PriceReaderApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/market-data-kv/config/price-reader.yaml");

    public static void main(String[] args) throws Exception {
        try (RingloomApplicationRunner runner =
                AvajeRingloomBootstrap.fromYaml(DEFAULT_CONFIG).start()) {
            System.out.printf("%s ready; press Ctrl+C to stop%n", ServiceNames.PRICE_READER);
            runner.awaitShutdown();
        }
    }
}

/** Read side of the {@code prices} store. */
@RingloomKvStore(name = ServiceNames.PRICES_STORE, serializer = "sbe")
interface PriceStore extends KvReaderOperations {
    /**
     * Zero-allocation on platform threads. The flyweight points into this thread's KV scratch and is
     * valid until the next KV read on the same thread.
     */
    @RingloomKvGet
    PriceSnapshotDecoder get(long instrumentId);
}

@RingloomClient(service = ServiceNames.PRICE_TERMINAL)
interface TerminalClient {
    @RingloomRequest(templateId = PriceResponseEncoder.TEMPLATE_ID, routing = RoutingMode.DIRECT)
    int replyPrice(PriceResponseDto payload, MessageContext replyTo);
}

@Component
final class PriceLookupHandler {

    private final PriceStore prices;
    private final TerminalClient terminal;
    private final PriceResponseDto response = new PriceResponseDto();

    @Inject
    PriceLookupHandler(@External PriceStore prices, @External TerminalClient terminal) {
        this.prices = prices;
        this.terminal = terminal;
    }

    /** Runs on the consumer thread ({@code consumerThread} dispatch), so one response DTO is enough. */
    @RingloomHandler(templateId = PriceRequestDecoder.TEMPLATE_ID)
    public int onPriceRequest(PriceRequestDecoder request, MessageContext context) {
        long instrumentId = request.instrumentId();
        PriceSnapshotDecoder snapshot = prices.get(instrumentId);
        response.instrumentId(instrumentId);
        if (snapshot == null) {
            // Missing, expired, or the store is not ready yet (no writer has published).
            response.bidNanos(0);
            response.askNanos(0);
            response.updateId(0);
            response.writerEpoch(0);
            response.status(RingloomStatus.KV_MISSING);
        } else {
            response.bidNanos(snapshot.bidNanos());
            response.askNanos(snapshot.askNanos());
            response.updateId(snapshot.updateId());
            response.writerEpoch(snapshot.writerEpoch());
            response.status(RingloomStatus.OK);
        }
        return terminal.replyPrice(response, context);
    }
}
