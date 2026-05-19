// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.marketdata.pricing;

import io.avaje.inject.Component;
import io.avaje.inject.External;
import io.ringloom.framework.RingloomApplicationRunner;
import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.annotation.RingloomPartitionKey;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.annotation.RoutingMode;
import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.framework.ioc.avaje.AvajeRingloomBootstrap;
import io.ringloom.framework.status.RingloomHandlerStatus;
import io.ringloom.samples.marketdata.common.Instruments;
import io.ringloom.samples.marketdata.common.ServiceNames;
import io.ringloom.samples.marketdata.sbe.QuoteRequestDecoder;
import io.ringloom.samples.marketdata.sbe.QuoteResponseDto;
import io.ringloom.samples.marketdata.sbe.QuoteResponseEncoder;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

@RingloomApplication(service = ServiceNames.PRICING_SERVICE)
public final class MarketDataPricingApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/market-data-avaje-sbe/config/pricing-service.yaml");

    public static void main(String[] args) throws Exception {
        try (RingloomApplicationRunner runner =
                AvajeRingloomBootstrap.fromYaml(DEFAULT_CONFIG).start()) {
            System.out.printf("%s ready; press Ctrl+C to stop%n", ServiceNames.PRICING_SERVICE);
            runner.awaitShutdown();
        }
    }
}

@RingloomClient(service = ServiceNames.TERMINAL)
interface TerminalClient {
    @RingloomRequest(templateId = QuoteResponseEncoder.TEMPLATE_ID, routing = RoutingMode.DIRECT)
    int replyQuote(QuoteResponseDto payload, MessageContext replyTo);
}

@Component
final class PricingHandlers {

    private final TerminalClient terminalClient;
    private final ThreadLocal<QuoteResponseDto> responses = ThreadLocal.withInitial(QuoteResponseDto::new);
    private final AtomicLong quoteSequence = new AtomicLong();

    @Inject
    PricingHandlers(@External TerminalClient terminalClient) {
        this.terminalClient = terminalClient;
    }

    @RingloomHandler(templateId = QuoteRequestDecoder.TEMPLATE_ID)
    public int onQuoteRequest(
            @RingloomPartitionKey("instrumentId") QuoteRequestDecoder request, MessageContext context) {
        int instrumentIndex = Instruments.index(request.instrumentId());
        if (instrumentIndex < 0) {
            return RingloomHandlerStatus.REQUEST_CANCELLED;
        }
        QuoteResponseDto response = responses.get();
        long mid = Instruments.MID_NANOS[instrumentIndex];
        long spread = Math.max(50_000L, request.quantity() * 1_000L);
        response.instrumentId(request.instrumentId());
        response.bidNanos(mid - spread);
        response.askNanos(mid + spread);
        response.quoteId(quoteSequence.incrementAndGet());
        return terminalClient.replyQuote(response, context);
    }
}
