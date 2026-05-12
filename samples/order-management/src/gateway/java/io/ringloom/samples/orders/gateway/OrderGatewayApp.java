// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.gateway;

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
import io.ringloom.samples.orders.sbe.NewOrderDecoder;
import io.ringloom.samples.orders.sbe.OrderRejectedDecoder;
import io.ringloom.samples.orders.sbe.OrderRejectedDto;
import io.ringloom.samples.orders.sbe.OrderRejectedEncoder;
import io.ringloom.samples.orders.sbe.RejectReason;
import io.ringloom.samples.orders.sbe.RiskCheckRequestDto;
import io.ringloom.samples.orders.sbe.RiskCheckRequestEncoder;
import io.ringloom.samples.orders.sbe.Stage;
import io.ringloom.samples.orders.sbe.Symbol;
import java.nio.file.Path;

@RingloomApplication(service = ServiceNames.ORDER_GATEWAY)
public final class OrderGatewayApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/order-management/config/order-gateway.yaml");

    public static void main(String[] args) throws Exception {
        try (RingloomApplicationRunner app =
                RingloomBootstrap.fromYaml(DEFAULT_CONFIG).start()) {
            System.out.printf("%s ready; press Ctrl+C to stop%n", ServiceNames.ORDER_GATEWAY);
            app.awaitShutdown();
        }
    }
}

@RingloomClient(service = ServiceNames.RISK_SERVICE)
interface RiskClient {
    @RingloomRequest(templateId = RiskCheckRequestEncoder.TEMPLATE_ID)
    int riskCheck(RiskCheckRequestDto payload);
}

@RingloomClient(service = ServiceNames.ORDER_SIMULATOR)
interface SimulatorClient {
    @RingloomRequest(templateId = OrderRejectedEncoder.TEMPLATE_ID)
    int reject(OrderRejectedDto payload);
}

@RingloomServiceComponent
final class GatewayHandlers {

    private final RiskCheckRequestDto riskRequest = new RiskCheckRequestDto();
    private final OrderRejectedDto rejection = new OrderRejectedDto();
    private long gatewaySequence = 1;
    private RiskClient riskClient;
    private SimulatorClient simulatorClient;

    @RingloomHandler(templateId = NewOrderDecoder.TEMPLATE_ID)
    public int onNewOrder(NewOrderDecoder order, MessageContext context) {
        RejectReason rejectReason = validate(order);
        if (rejectReason != null) {
            return reject(order.accountId(), order.orderId(), order.symbol(), rejectReason, Stage.GATEWAY, context);
        }
        riskRequest.gatewaySequence(gatewaySequence++);
        riskRequest.accountId(order.accountId());
        riskRequest.orderId(order.orderId());
        riskRequest.symbol(order.symbol());
        riskRequest.side(order.side());
        riskRequest.timeInForce(order.timeInForce());
        riskRequest.quantity(order.quantity());
        riskRequest.priceNanos(order.priceNanos());
        int status = riskClient(context).riskCheck(riskRequest);
        if (status != 0) {
            return reject(
                    order.accountId(),
                    order.orderId(),
                    order.symbol(),
                    RejectReason.SYSTEM_BUSY,
                    Stage.GATEWAY,
                    context);
        }
        return status;
    }

    @RingloomHandler(templateId = OrderRejectedDecoder.TEMPLATE_ID)
    public int onRejected(OrderRejectedDecoder rejected, MessageContext context) {
        OrderRejectedDto.decodeWith(rejected, rejection);
        return simulatorClient(context).reject(rejection);
    }

    private RejectReason validate(NewOrderDecoder order) {
        if (StaticTables.symbolIndex(order.symbol()) < 0) {
            return RejectReason.UNKNOWN_SYMBOL;
        }
        if (order.quantity() <= 0) {
            return RejectReason.ZERO_QUANTITY;
        }
        if (!StaticTables.validPrice(order.priceNanos())) {
            return RejectReason.BAD_PRICE;
        }
        return null;
    }

    private int reject(
            long accountId, long orderId, Symbol symbol, RejectReason reason, Stage stage, MessageContext context) {
        rejection.accountId(accountId);
        rejection.orderId(orderId);
        rejection.symbol(symbol);
        rejection.reason(reason);
        rejection.stage(stage);
        return simulatorClient(context).reject(rejection);
    }

    private RiskClient riskClient(MessageContext context) {
        if (riskClient == null) {
            riskClient = context.runtime().generatedClient(RiskClient.class);
        }
        return riskClient;
    }

    private SimulatorClient simulatorClient(MessageContext context) {
        if (simulatorClient == null) {
            simulatorClient = context.runtime().generatedClient(SimulatorClient.class);
        }
        return simulatorClient;
    }
}
