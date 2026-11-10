// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.marketdatakv.terminal;

import io.ringloom.framework.RingloomApplicationRunner;
import io.ringloom.framework.annotation.RequestMode;
import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.ioc.avaje.AvajeRingloomBootstrap;
import io.ringloom.framework.request.RequestTimeout;
import io.ringloom.framework.request.RingloomRequestException;
import io.ringloom.samples.marketdatakv.common.Instruments;
import io.ringloom.samples.marketdatakv.common.ServiceNames;
import io.ringloom.samples.marketdatakv.sbe.PriceRequestDto;
import io.ringloom.samples.marketdatakv.sbe.PriceRequestEncoder;
import io.ringloom.samples.marketdatakv.sbe.PriceResponseDecoder;
import io.ringloom.samples.marketdatakv.sbe.PriceResponseDto;
import io.ringloom.service.RingloomStatus;
import java.nio.file.Path;

/**
 * Asks {@code price-reader} for every instrument once a second and prints the price and the writer
 * epoch that produced it, so a standby takeover is visible as an epoch change.
 */
@RingloomApplication(service = ServiceNames.PRICE_TERMINAL)
public final class PriceTerminalApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/market-data-kv/config/price-terminal.yaml");
    private static final RequestTimeout REQUEST_TIMEOUT = RequestTimeout.ofMillis(2_000);

    public static void main(String[] args) throws Exception {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 120;
        try (RingloomApplicationRunner runner =
                AvajeRingloomBootstrap.fromYaml(DEFAULT_CONFIG).start()) {
            Thread.sleep(1_000L);
            PriceReaderClient reader = runner.runtime().generatedClient(PriceReaderClient.class);
            PriceRequestDto request = new PriceRequestDto();
            for (int round = 0; round < rounds; round++) {
                for (long instrumentId : Instruments.IDS) {
                    request.instrumentId(instrumentId);
                    try {
                        print(reader.requestPrice(request, REQUEST_TIMEOUT));
                    } catch (RingloomRequestException ex) {
                        System.out.printf("%s request failed: %s%n", Instruments.name(instrumentId), ex.getMessage());
                    }
                }
                Thread.sleep(1_000L);
            }
        }
    }

    private static void print(PriceResponseDto price) {
        if (price.status() != RingloomStatus.OK) {
            System.out.printf("%s no price yet (status %d)%n", Instruments.name(price.instrumentId()), price.status());
            return;
        }
        System.out.printf(
                "%s bid=%.4f ask=%.4f update=%d writerEpoch=%d%n",
                Instruments.name(price.instrumentId()),
                price.bidNanos() / 1_000_000_000.0,
                price.askNanos() / 1_000_000_000.0,
                price.updateId(),
                price.writerEpoch());
    }
}

@RingloomClient(service = ServiceNames.PRICE_READER)
interface PriceReaderClient {
    @RingloomRequest(
            templateId = PriceRequestEncoder.TEMPLATE_ID,
            responseTemplateId = PriceResponseDecoder.TEMPLATE_ID,
            mode = RequestMode.VIRTUAL_THREAD_BLOCKING)
    PriceResponseDto requestPrice(PriceRequestDto payload, RequestTimeout timeout)
            throws RingloomRequestException, InterruptedException;
}
