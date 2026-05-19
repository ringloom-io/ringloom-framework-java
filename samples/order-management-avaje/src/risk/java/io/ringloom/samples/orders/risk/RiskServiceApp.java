// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.risk;

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
import io.ringloom.samples.orders.model.OrderRejected;
import io.ringloom.samples.orders.model.RejectReason;
import io.ringloom.samples.orders.model.RiskAccepted;
import io.ringloom.samples.orders.model.RiskCheckRequest;
import io.ringloom.samples.orders.model.Stage;
import io.ringloom.samples.orders.model.TemplateIds;
import jakarta.inject.Inject;
import java.nio.file.Path;

@RingloomApplication(service = ServiceNames.RISK_SERVICE)
public final class RiskServiceApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/order-management-avaje/config/risk-service.yaml");

    public static void main(String[] args) throws Exception {
        try (RingloomApplicationRunner runner =
                AvajeRingloomBootstrap.fromYaml(DEFAULT_CONFIG).start()) {
            System.out.printf("%s ready; press Ctrl+C to stop%n", ServiceNames.RISK_SERVICE);
            runner.awaitShutdown();
        }
    }
}

@RingloomClient(service = ServiceNames.MATCHING_ENGINE)
interface MatchingClient {
    @RingloomRequest(templateId = TemplateIds.RISK_ACCEPTED)
    int accepted(RiskAccepted payload);
}

@RingloomClient(service = ServiceNames.ORDER_GATEWAY)
interface GatewayClient {
    @RingloomRequest(templateId = TemplateIds.ORDER_REJECTED)
    int rejected(OrderRejected payload);
}

@Component
final class RiskHandlers {

    private final MatchingClient matchingClient;
    private final GatewayClient gatewayClient;
    private final long[] accountRemaining = StaticTables.CREDIT_NANOS.clone();
    private final long[] symbolNotional = new long[StaticTables.SYMBOL_COUNT];

    @Inject
    RiskHandlers(@External MatchingClient matchingClient, @External GatewayClient gatewayClient) {
        this.matchingClient = matchingClient;
        this.gatewayClient = gatewayClient;
    }

    @RingloomHandler(templateId = TemplateIds.RISK_CHECK_REQUEST)
    public int onRiskCheck(RiskCheckRequest request) {
        int accountIndex = StaticTables.accountIndex(request.accountId());
        int symbolIndex = StaticTables.symbolIndex(request.symbol());
        if (accountIndex < 0 || symbolIndex < 0) {
            return reject(request, RejectReason.RISK_CREDIT);
        }
        long notional = request.quantity() * request.priceNanos();
        if (notional > accountRemaining[accountIndex]) {
            return reject(request, RejectReason.RISK_CREDIT);
        }
        if (symbolNotional[symbolIndex] + notional > StaticTables.SYMBOL_NOTIONAL_LIMIT_NANOS[symbolIndex]) {
            return reject(request, RejectReason.RISK_SYMBOL_LIMIT);
        }
        accountRemaining[accountIndex] -= notional;
        symbolNotional[symbolIndex] += notional;
        RiskAccepted accepted = new RiskAccepted(
                request.gatewaySequence(),
                notional,
                request.accountId(),
                request.orderId(),
                request.symbol(),
                request.side(),
                request.timeInForce(),
                request.quantity(),
                request.priceNanos());
        int status = matchingClient.accepted(accepted);
        if (status != 0) {
            return reject(request, RejectReason.SYSTEM_BUSY);
        }
        return status;
    }

    private int reject(RiskCheckRequest request, RejectReason reason) {
        return gatewayClient.rejected(
                new OrderRejected(request.accountId(), request.orderId(), request.symbol(), reason, Stage.RISK));
    }
}
