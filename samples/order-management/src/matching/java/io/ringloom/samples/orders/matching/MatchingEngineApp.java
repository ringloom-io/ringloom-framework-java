// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.matching;

import io.ringloom.framework.RingloomApplicationRunner;
import io.ringloom.framework.RingloomBootstrap;
import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.annotation.RingloomServiceComponent;
import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.samples.orders.common.ServiceNames;
import io.ringloom.samples.orders.common.StaticTables;
import io.ringloom.samples.orders.sbe.FillDto;
import io.ringloom.samples.orders.sbe.FillEncoder;
import io.ringloom.samples.orders.sbe.RiskAcceptedDecoder;
import java.nio.file.Path;

@RingloomApplication(service = ServiceNames.MATCHING_ENGINE)
public final class MatchingEngineApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/order-management/config/matching-engine.yaml");

    public static void main(String[] args) throws Exception {
        try (RingloomApplicationRunner app =
                RingloomBootstrap.fromYaml(DEFAULT_CONFIG).start()) {
            System.out.printf("%s ready; press Ctrl+C to stop%n", ServiceNames.MATCHING_ENGINE);
            app.awaitShutdown();
        }
    }
}

@RingloomClient(service = ServiceNames.EXECUTION_SERVICE)
interface ExecutionClient {
    @RingloomRequest(templateId = FillEncoder.TEMPLATE_ID)
    int fill(FillDto payload);
}

@RingloomServiceComponent
final class MatchingHandlers {

    private final long[] available = {
        10_000_000L, 10_000_000L, 10_000_000L, 10_000_000L,
    };
    private final FillDto fill = new FillDto();
    private ExecutionClient executionClient;

    @RingloomHandler(templateId = RiskAcceptedDecoder.TEMPLATE_ID)
    public int onAccepted(RiskAcceptedDecoder accepted, MessageContext context) {
        int symbolIndex = StaticTables.symbolIndex(accepted.symbol());
        if (symbolIndex < 0) {
            return 0;
        }
        long fillQuantity = Math.min(available[symbolIndex], accepted.quantity());
        if (fillQuantity <= 0) {
            return 0;
        }
        available[symbolIndex] -= fillQuantity;
        fill.accountId(accepted.accountId());
        fill.orderId(accepted.orderId());
        fill.symbol(accepted.symbol());
        fill.side(accepted.side());
        fill.quantity(fillQuantity);
        fill.priceNanos(accepted.priceNanos());
        fill.gatewaySequence(accepted.gatewaySequence());
        return executionClient(context).fill(fill);
    }

    private ExecutionClient executionClient(MessageContext context) {
        if (executionClient == null) {
            executionClient = context.runtime().generatedClient(ExecutionClient.class);
        }
        return executionClient;
    }
}
