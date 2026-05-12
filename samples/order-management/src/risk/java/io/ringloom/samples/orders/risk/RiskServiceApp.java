// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.risk;

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
import io.ringloom.samples.orders.sbe.OrderRejectedDto;
import io.ringloom.samples.orders.sbe.OrderRejectedEncoder;
import io.ringloom.samples.orders.sbe.RejectReason;
import io.ringloom.samples.orders.sbe.RiskAcceptedDto;
import io.ringloom.samples.orders.sbe.RiskAcceptedEncoder;
import io.ringloom.samples.orders.sbe.RiskCheckRequestDecoder;
import io.ringloom.samples.orders.sbe.Stage;
import java.nio.file.Path;

@RingloomApplication(service = ServiceNames.RISK_SERVICE)
public final class RiskServiceApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/order-management/config/risk-service.yaml");

    public static void main(String[] args) throws Exception {
        try (RingloomApplicationRunner app =
                RingloomBootstrap.fromYaml(DEFAULT_CONFIG).start()) {
            System.out.printf("%s ready; press Ctrl+C to stop%n", ServiceNames.RISK_SERVICE);
            app.awaitShutdown();
        }
    }
}

@RingloomClient(service = ServiceNames.MATCHING_ENGINE)
interface MatchingClient {
    @RingloomRequest(templateId = RiskAcceptedEncoder.TEMPLATE_ID)
    int accepted(RiskAcceptedDto payload);
}

@RingloomClient(service = ServiceNames.ORDER_GATEWAY)
interface GatewayClient {
    @RingloomRequest(templateId = OrderRejectedEncoder.TEMPLATE_ID)
    int rejected(OrderRejectedDto payload);
}

@RingloomServiceComponent
final class RiskHandlers {

    private final long[] accountRemaining = StaticTables.CREDIT_NANOS.clone();
    private final long[] symbolNotional = new long[StaticTables.SYMBOL_COUNT];
    private final RiskAcceptedDto accepted = new RiskAcceptedDto();
    private final OrderRejectedDto rejection = new OrderRejectedDto();
    private MatchingClient matchingClient;
    private GatewayClient gatewayClient;

    @RingloomHandler(templateId = RiskCheckRequestDecoder.TEMPLATE_ID)
    public int onRiskCheck(RiskCheckRequestDecoder request, MessageContext context) {
        int accountIndex = StaticTables.accountIndex(request.accountId());
        int symbolIndex = StaticTables.symbolIndex(request.symbol());
        if (accountIndex < 0 || symbolIndex < 0) {
            return reject(request, RejectReason.RISK_CREDIT, context);
        }
        long notional = request.quantity() * request.priceNanos();
        if (notional > accountRemaining[accountIndex]) {
            return reject(request, RejectReason.RISK_CREDIT, context);
        }
        if (symbolNotional[symbolIndex] + notional > StaticTables.SYMBOL_NOTIONAL_LIMIT_NANOS[symbolIndex]) {
            return reject(request, RejectReason.RISK_SYMBOL_LIMIT, context);
        }
        accountRemaining[accountIndex] -= notional;
        symbolNotional[symbolIndex] += notional;
        accepted.gatewaySequence(request.gatewaySequence());
        accepted.acceptedNotionalNanos(notional);
        accepted.accountId(request.accountId());
        accepted.orderId(request.orderId());
        accepted.symbol(request.symbol());
        accepted.side(request.side());
        accepted.timeInForce(request.timeInForce());
        accepted.quantity(request.quantity());
        accepted.priceNanos(request.priceNanos());
        int status = matchingClient(context).accepted(accepted);
        if (status != 0) {
            return reject(request, RejectReason.SYSTEM_BUSY, context);
        }
        return status;
    }

    private int reject(RiskCheckRequestDecoder request, RejectReason reason, MessageContext context) {
        rejection.accountId(request.accountId());
        rejection.orderId(request.orderId());
        rejection.symbol(request.symbol());
        rejection.reason(reason);
        rejection.stage(Stage.RISK);
        return gatewayClient(context).rejected(rejection);
    }

    private MatchingClient matchingClient(MessageContext context) {
        if (matchingClient == null) {
            matchingClient = context.runtime().generatedClient(MatchingClient.class);
        }
        return matchingClient;
    }

    private GatewayClient gatewayClient(MessageContext context) {
        if (gatewayClient == null) {
            gatewayClient = context.runtime().generatedClient(GatewayClient.class);
        }
        return gatewayClient;
    }
}
