// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.pricing.service;

import io.avaje.inject.Component;
import io.avaje.inject.External;
import io.ringloom.framework.RingloomApplicationRunner;
import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.annotation.RoutingMode;
import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.framework.ioc.avaje.AvajeRingloomBootstrap;
import io.ringloom.samples.pricing.common.ServiceNames;
import io.ringloom.samples.pricing.common.TemplateIds;
import io.ringloom.samples.pricing.model.PriceQuote;
import io.ringloom.samples.pricing.model.PriceQuoteRequest;
import jakarta.inject.Inject;
import java.nio.file.Path;

@RingloomApplication(service = ServiceNames.PRICING_SERVICE)
public final class PricingServiceApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/pricing-requests-avaje/config/pricing-service.yaml");

    public static void main(String[] args) throws Exception {
        try (RingloomApplicationRunner runner =
                AvajeRingloomBootstrap.fromYaml(DEFAULT_CONFIG).start()) {
            System.out.printf("%s ready; press Ctrl+C to stop%n", ServiceNames.PRICING_SERVICE);
            runner.awaitShutdown();
        }
    }
}

@RingloomClient(service = ServiceNames.PRICING_TERMINAL)
interface TerminalClient {
    @RingloomRequest(templateId = TemplateIds.PRICE_QUOTE, serializer = "fory", routing = RoutingMode.DIRECT)
    int replyQuote(PriceQuote payload, MessageContext replyTo);
}

@Component
final class PricingHandlers {

    private final TerminalClient terminalClient;
    private long quoteSequence = 1;

    @Inject
    PricingHandlers(@External TerminalClient terminalClient) {
        this.terminalClient = terminalClient;
    }

    @RingloomHandler(templateId = TemplateIds.PRICE_QUOTE_REQUEST)
    public int onQuoteRequest(PriceQuoteRequest request, MessageContext context) {
        long mid = midPriceNanos(request.symbol());
        long spread = Math.max(50_000L, request.quantity() * 1_000L);
        PriceQuote quote = new PriceQuote(request.symbol(), mid - spread, mid + spread, quoteSequence++);
        return terminalClient.replyQuote(quote, context);
    }

    private static long midPriceNanos(String symbol) {
        return switch (symbol) {
            case "AAPL" -> 189_420_000_000L;
            case "MSFT" -> 427_130_000_000L;
            case "NVDA" -> 924_610_000_000L;
            case "EURUSD" -> 1_086_500_000L;
            default -> 100_000_000_000L + Math.floorMod(symbol.hashCode(), 10_000) * 1_000_000L;
        };
    }
}
