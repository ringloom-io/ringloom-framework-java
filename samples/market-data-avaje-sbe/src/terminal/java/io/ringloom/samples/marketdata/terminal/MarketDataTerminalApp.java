// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.marketdata.terminal;

import io.ringloom.framework.RingloomApplicationRunner;
import io.ringloom.framework.annotation.RequestMode;
import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.ioc.avaje.AvajeRingloomBootstrap;
import io.ringloom.framework.request.RequestTimeout;
import io.ringloom.framework.request.RingloomRequestException;
import io.ringloom.samples.marketdata.common.Instruments;
import io.ringloom.samples.marketdata.common.ServiceNames;
import io.ringloom.samples.marketdata.sbe.QuoteRequestDto;
import io.ringloom.samples.marketdata.sbe.QuoteRequestEncoder;
import io.ringloom.samples.marketdata.sbe.QuoteResponseDecoder;
import io.ringloom.samples.marketdata.sbe.QuoteResponseDto;
import java.nio.file.Path;

@RingloomApplication(service = ServiceNames.TERMINAL)
public final class MarketDataTerminalApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/market-data-avaje-sbe/config/terminal.yaml");
    private static final RequestTimeout REQUEST_TIMEOUT = RequestTimeout.ofMillis(2_000);

    public static void main(String[] args) throws Exception {
        try (RingloomApplicationRunner runner =
                AvajeRingloomBootstrap.fromYaml(DEFAULT_CONFIG).start()) {
            Thread.sleep(1_000L);
            MarketDataClient marketData = runner.runtime().generatedClient(MarketDataClient.class);
            QuoteRequestDto request = new QuoteRequestDto();
            for (int instrumentId : Instruments.IDS) {
                request.instrumentId(instrumentId);
                request.quantity(100);
                printQuote(marketData.requestQuote(request, REQUEST_TIMEOUT));
            }
        }
    }

    private static void printQuote(QuoteResponseDto quote) {
        System.out.printf(
                "%s quote #%d bid=%.4f ask=%.4f%n",
                Instruments.name(quote.instrumentId()),
                quote.quoteId(),
                quote.bidNanos() / 1_000_000_000.0,
                quote.askNanos() / 1_000_000_000.0);
    }
}

@RingloomClient(service = ServiceNames.PRICING_SERVICE)
interface MarketDataClient {
    @RingloomRequest(
            templateId = QuoteRequestEncoder.TEMPLATE_ID,
            responseTemplateId = QuoteResponseDecoder.TEMPLATE_ID,
            mode = RequestMode.VIRTUAL_THREAD_BLOCKING)
    QuoteResponseDto requestQuote(QuoteRequestDto payload, RequestTimeout timeout)
            throws RingloomRequestException, InterruptedException;
}
