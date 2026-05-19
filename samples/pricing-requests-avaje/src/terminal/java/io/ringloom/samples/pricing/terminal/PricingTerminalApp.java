// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.pricing.terminal;

import io.ringloom.framework.RingloomApplicationRunner;
import io.ringloom.framework.annotation.RequestMode;
import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.ioc.avaje.AvajeRingloomBootstrap;
import io.ringloom.framework.request.RequestTimeout;
import io.ringloom.framework.request.RingloomRequestException;
import io.ringloom.samples.pricing.common.ServiceNames;
import io.ringloom.samples.pricing.common.TemplateIds;
import io.ringloom.samples.pricing.model.PriceQuote;
import io.ringloom.samples.pricing.model.PriceQuoteRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@RingloomApplication(service = ServiceNames.PRICING_TERMINAL)
public final class PricingTerminalApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/pricing-requests-avaje/config/pricing-terminal.yaml");
    private static final RequestTimeout REQUEST_TIMEOUT = RequestTimeout.ofMillis(2_000);

    public static void main(String[] args) throws Exception {
        try (RingloomApplicationRunner runner =
                AvajeRingloomBootstrap.fromYaml(DEFAULT_CONFIG).start()) {
            Thread.sleep(1_000L);
            PricingClient pricing = runner.runtime().generatedClient(PricingClient.class);
            List<Thread> requests = new ArrayList<>();
            for (String symbol : List.of("AAPL", "MSFT", "NVDA", "EURUSD")) {
                requests.add(
                        Thread.ofVirtual().name("quote-request-" + symbol).start(() -> requestQuote(pricing, symbol)));
            }
            for (Thread request : requests) {
                request.join();
            }
        }
    }

    private static void requestQuote(PricingClient pricing, String symbol) {
        try {
            PriceQuote quote = pricing.requestQuote(new PriceQuoteRequest(symbol, 100), REQUEST_TIMEOUT);
            System.out.printf(
                    "%s quote #%d bid=%.4f ask=%.4f%n",
                    quote.symbol(),
                    quote.quoteId(),
                    quote.bidNanos() / 1_000_000_000.0,
                    quote.askNanos() / 1_000_000_000.0);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RingloomRequestException ex) {
            System.out.printf("%s quote failed: status=%d%n", symbol, ex.status());
        }
    }
}

@RingloomClient(service = ServiceNames.PRICING_SERVICE)
interface PricingClient {
    @RingloomRequest(
            templateId = TemplateIds.PRICE_QUOTE_REQUEST,
            responseTemplateId = TemplateIds.PRICE_QUOTE,
            serializer = "fory",
            mode = RequestMode.VIRTUAL_THREAD_BLOCKING)
    PriceQuote requestQuote(PriceQuoteRequest payload, RequestTimeout timeout)
            throws RingloomRequestException, InterruptedException;
}
