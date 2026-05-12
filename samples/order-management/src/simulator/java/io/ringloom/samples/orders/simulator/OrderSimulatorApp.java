// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.simulator;

import io.ringloom.framework.RingloomApplicationRunner;
import io.ringloom.framework.RingloomBootstrap;
import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.annotation.RingloomServiceComponent;
import io.ringloom.samples.orders.common.ServiceNames;
import io.ringloom.samples.orders.common.StaticTables;
import io.ringloom.samples.orders.sbe.NewOrderDto;
import io.ringloom.samples.orders.sbe.NewOrderEncoder;
import io.ringloom.samples.orders.sbe.OrderRejectedDecoder;
import io.ringloom.samples.orders.sbe.Side;
import io.ringloom.samples.orders.sbe.Symbol;
import io.ringloom.samples.orders.sbe.TimeInForce;
import io.ringloom.service.RingloomStatus;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;

@RingloomApplication(service = ServiceNames.ORDER_SIMULATOR)
public final class OrderSimulatorApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/order-management/config/order-simulator.yaml");
    private final NewOrderDto outboundOrder = new NewOrderDto();

    public static void main(String[] args) throws Exception {
        int orders = args.length > 0 ? Integer.parseInt(args[0]) : 1_000;
        int ratePerSecond = args.length > 1 ? Integer.parseInt(args[1]) : 10_000;
        new OrderSimulatorApp().run(orders, ratePerSecond);
    }

    private void run(int orders, int ratePerSecond) throws Exception {
        try (RingloomApplicationRunner app =
                RingloomBootstrap.fromYaml(DEFAULT_CONFIG).start()) {
            Thread.sleep(1_000L);
            GatewayClient gateway = app.runtime().generatedClient(GatewayClient.class);
            long sent = generate(gateway, orders, ratePerSecond);
            Thread.sleep(2_000L);
            System.out.printf(
                    "order-simulator finished: generated=%d sent=%d rejected=%d%n",
                    orders, sent, SimulatorHandlers.rejectedCount);
        }
    }

    private long generate(GatewayClient gateway, int orders, int ratePerSecond) {
        long sent = 0;
        long intervalNanos = ratePerSecond <= 0 ? 0 : 1_000_000_000L / ratePerSecond;
        for (long sequence = 1; sequence <= orders; sequence++) {
            fillDeterministicOrder(sequence);
            int status = gateway.newOrder(outboundOrder);
            if (status == RingloomStatus.OK) {
                sent++;
            }
            if (intervalNanos > 0) {
                LockSupport.parkNanos(intervalNanos);
            }
        }
        return sent;
    }

    private void fillDeterministicOrder(long sequence) {
        int account = StaticTables.ACCOUNTS[(int) ((sequence * 7 + sequence / 13) % StaticTables.ACCOUNTS.length)];
        Symbol symbol = StaticTables.SYMBOLS[(int) ((sequence * 5 + sequence / 17) % StaticTables.SYMBOLS.length)];
        long quantity = sequence % 29 == 0 ? 0 : (sequence % 250) + 1;
        long price = sequence % 31 == 0
                ? StaticTables.MAX_PRICE_NANOS + 1_000_000_000L
                : StaticTables.deterministicPrice(sequence, symbol);
        outboundOrder.accountId(account);
        outboundOrder.orderId(sequence);
        outboundOrder.symbol(symbol);
        outboundOrder.side(sequence % 2 == 0 ? Side.BUY : Side.SELL);
        outboundOrder.timeInForce(TimeInForce.DAY);
        outboundOrder.quantity(quantity);
        outboundOrder.priceNanos(price);
    }
}

@RingloomClient(service = ServiceNames.ORDER_GATEWAY)
interface GatewayClient {
    @RingloomRequest(templateId = NewOrderEncoder.TEMPLATE_ID)
    int newOrder(NewOrderDto payload);
}

@RingloomServiceComponent
final class SimulatorHandlers {

    static volatile long rejectedCount;

    @RingloomHandler(templateId = OrderRejectedDecoder.TEMPLATE_ID)
    public void onRejected(OrderRejectedDecoder rejected) {
        rejectedCount++;
    }
}
