// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.simulator;

import io.avaje.inject.BeanScope;
import io.avaje.inject.Component;
import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.samples.orders.common.AvajeRingloom;
import io.ringloom.samples.orders.common.ServiceNames;
import io.ringloom.samples.orders.common.StaticTables;
import io.ringloom.samples.orders.model.NewOrder;
import io.ringloom.samples.orders.model.OrderRejected;
import io.ringloom.samples.orders.model.Side;
import io.ringloom.samples.orders.model.Symbol;
import io.ringloom.samples.orders.model.TemplateIds;
import io.ringloom.samples.orders.model.TimeInForce;
import io.ringloom.service.RingloomStatus;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;

@RingloomApplication(service = ServiceNames.ORDER_SIMULATOR)
public final class OrderSimulatorApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/order-management-avaje/config/order-simulator.yaml");

    public static void main(String[] args) throws Exception {
        int orders = args.length > 0 ? Integer.parseInt(args[0]) : 1_000;
        int ratePerSecond = args.length > 1 ? Integer.parseInt(args[1]) : 10_000;
        new OrderSimulatorApp().run(orders, ratePerSecond);
    }

    private void run(int orders, int ratePerSecond) throws Exception {
        try (BeanScope scope = AvajeRingloom.start(DEFAULT_CONFIG)) {
            Thread.sleep(1_000L);
            GatewayClient gateway = scope.get(GatewayClient.class);
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
            int status = gateway.newOrder(deterministicOrder(sequence));
            if (status == RingloomStatus.OK) {
                sent++;
            }
            if (intervalNanos > 0) {
                LockSupport.parkNanos(intervalNanos);
            }
        }
        return sent;
    }

    private NewOrder deterministicOrder(long sequence) {
        int account = StaticTables.ACCOUNTS[(int) ((sequence * 7 + sequence / 13) % StaticTables.ACCOUNTS.length)];
        Symbol symbol = StaticTables.SYMBOLS[(int) ((sequence * 5 + sequence / 17) % StaticTables.SYMBOLS.length)];
        long quantity = sequence % 29 == 0 ? 0 : (sequence % 250) + 1;
        long price = sequence % 31 == 0
                ? StaticTables.MAX_PRICE_NANOS + 1_000_000_000L
                : StaticTables.deterministicPrice(sequence, symbol);
        return new NewOrder(
                account, sequence, symbol, sequence % 2 == 0 ? Side.BUY : Side.SELL, TimeInForce.DAY, quantity, price);
    }
}

@RingloomClient(service = ServiceNames.ORDER_GATEWAY)
interface GatewayClient {
    @RingloomRequest(templateId = TemplateIds.NEW_ORDER)
    int newOrder(NewOrder payload);
}

@Component
final class SimulatorHandlers {

    static volatile long rejectedCount;

    @RingloomHandler(templateId = TemplateIds.ORDER_REJECTED)
    public void onRejected(OrderRejected rejected) {
        rejectedCount++;
    }
}
