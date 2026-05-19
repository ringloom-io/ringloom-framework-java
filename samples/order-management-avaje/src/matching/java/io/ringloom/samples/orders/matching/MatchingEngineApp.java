// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.matching;

import io.avaje.inject.Component;
import io.avaje.inject.External;
import io.ringloom.framework.RingloomApplicationRunner;
import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.ioc.avaje.AvajeRingloomBootstrap;
import io.ringloom.samples.orders.common.ServiceNames;
import io.ringloom.samples.orders.common.StaticTables;
import io.ringloom.samples.orders.model.Fill;
import io.ringloom.samples.orders.model.RiskAccepted;
import io.ringloom.samples.orders.model.TemplateIds;
import jakarta.inject.Inject;
import java.nio.file.Path;

@RingloomApplication(service = ServiceNames.MATCHING_ENGINE)
public final class MatchingEngineApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/order-management-avaje/config/matching-engine.yaml");

    public static void main(String[] args) throws Exception {
        try (RingloomApplicationRunner runner =
                AvajeRingloomBootstrap.fromYaml(DEFAULT_CONFIG).start()) {
            System.out.printf("%s ready; press Ctrl+C to stop%n", ServiceNames.MATCHING_ENGINE);
            runner.awaitShutdown();
        }
    }
}

@RingloomClient(service = ServiceNames.EXECUTION_SERVICE)
interface ExecutionClient {
    @RingloomRequest(templateId = TemplateIds.FILL)
    int fill(Fill payload);
}

@Component
final class MatchingHandlers {

    private final ExecutionClient executionClient;
    private final long[] available = {
        10_000_000L, 10_000_000L, 10_000_000L, 10_000_000L,
    };

    @Inject
    MatchingHandlers(@External ExecutionClient executionClient) {
        this.executionClient = executionClient;
    }

    @RingloomHandler(templateId = TemplateIds.RISK_ACCEPTED)
    public int onAccepted(RiskAccepted accepted) {
        int symbolIndex = StaticTables.symbolIndex(accepted.symbol());
        if (symbolIndex < 0) {
            return 0;
        }
        long fillQuantity = Math.min(available[symbolIndex], accepted.quantity());
        if (fillQuantity <= 0) {
            return 0;
        }
        available[symbolIndex] -= fillQuantity;
        return executionClient.fill(new Fill(
                accepted.accountId(),
                accepted.orderId(),
                accepted.symbol(),
                accepted.side(),
                fillQuantity,
                accepted.priceNanos(),
                accepted.gatewaySequence()));
    }
}
