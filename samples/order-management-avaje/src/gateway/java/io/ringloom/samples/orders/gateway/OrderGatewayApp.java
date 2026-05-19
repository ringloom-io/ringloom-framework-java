// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.gateway;

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
import io.ringloom.samples.orders.model.NewOrder;
import io.ringloom.samples.orders.model.OrderRejected;
import io.ringloom.samples.orders.model.RejectReason;
import io.ringloom.samples.orders.model.RiskCheckRequest;
import io.ringloom.samples.orders.model.Stage;
import io.ringloom.samples.orders.model.Symbol;
import io.ringloom.samples.orders.model.TemplateIds;
import jakarta.inject.Inject;
import java.nio.file.Path;

@RingloomApplication(service = ServiceNames.ORDER_GATEWAY)
public final class OrderGatewayApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/order-management-avaje/config/order-gateway.yaml");

    public static void main(String[] args) throws Exception {
        try (RingloomApplicationRunner runner =
                AvajeRingloomBootstrap.fromYaml(DEFAULT_CONFIG).start()) {
            System.out.printf("%s ready; press Ctrl+C to stop%n", ServiceNames.ORDER_GATEWAY);
            runner.awaitShutdown();
        }
    }
}

@RingloomClient(service = ServiceNames.RISK_SERVICE)
interface RiskClient {
    @RingloomRequest(templateId = TemplateIds.RISK_CHECK_REQUEST)
    int riskCheck(RiskCheckRequest payload);
}

@RingloomClient(service = ServiceNames.ORDER_SIMULATOR)
interface SimulatorClient {
    @RingloomRequest(templateId = TemplateIds.ORDER_REJECTED)
    int reject(OrderRejected payload);
}

@Component
final class GatewayHandlers {

    private final RiskClient riskClient;
    private final SimulatorClient simulatorClient;
    private long gatewaySequence = 1;

    @Inject
    GatewayHandlers(@External RiskClient riskClient, @External SimulatorClient simulatorClient) {
        this.riskClient = riskClient;
        this.simulatorClient = simulatorClient;
    }

    @RingloomHandler(templateId = TemplateIds.NEW_ORDER)
    public int onNewOrder(NewOrder order) {
        RejectReason rejectReason = validate(order);
        if (rejectReason != null) {
            return reject(order.accountId(), order.orderId(), order.symbol(), rejectReason, Stage.GATEWAY);
        }
        RiskCheckRequest riskRequest = new RiskCheckRequest(
                gatewaySequence++,
                order.accountId(),
                order.orderId(),
                order.symbol(),
                order.side(),
                order.timeInForce(),
                order.quantity(),
                order.priceNanos());
        int status = riskClient.riskCheck(riskRequest);
        if (status != 0) {
            return reject(order.accountId(), order.orderId(), order.symbol(), RejectReason.SYSTEM_BUSY, Stage.GATEWAY);
        }
        return status;
    }

    @RingloomHandler(templateId = TemplateIds.ORDER_REJECTED)
    public int onRejected(OrderRejected rejected) {
        return simulatorClient.reject(rejected);
    }

    private RejectReason validate(NewOrder order) {
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

    private int reject(long accountId, long orderId, Symbol symbol, RejectReason reason, Stage stage) {
        return simulatorClient.reject(new OrderRejected(accountId, orderId, symbol, reason, stage));
    }
}
