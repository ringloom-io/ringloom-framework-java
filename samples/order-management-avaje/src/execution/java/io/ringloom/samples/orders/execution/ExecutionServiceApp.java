// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.execution;

import io.avaje.inject.BeanScope;
import io.avaje.inject.Component;
import io.avaje.inject.External;
import io.ringloom.framework.RingloomApplicationRunner;
import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.samples.orders.common.AvajeRingloom;
import io.ringloom.samples.orders.common.ServiceNames;
import io.ringloom.samples.orders.model.ExecutionReport;
import io.ringloom.samples.orders.model.ExecutionStatus;
import io.ringloom.samples.orders.model.Fill;
import io.ringloom.samples.orders.model.TemplateIds;
import jakarta.inject.Inject;
import java.nio.file.Path;

@RingloomApplication(service = ServiceNames.EXECUTION_SERVICE)
public final class ExecutionServiceApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/order-management-avaje/config/execution-service.yaml");

    public static void main(String[] args) throws Exception {
        try (BeanScope scope = AvajeRingloom.start(DEFAULT_CONFIG)) {
            System.out.printf("%s ready; press Ctrl+C to stop%n", ServiceNames.EXECUTION_SERVICE);
            scope.get(RingloomApplicationRunner.class).awaitShutdown();
        }
    }
}

@RingloomClient(service = ServiceNames.PORTFOLIO_SERVICE)
interface PortfolioClient {
    @RingloomRequest(templateId = TemplateIds.EXECUTION_REPORT)
    int executionReport(ExecutionReport payload);
}

@Component
final class ExecutionHandlers {

    private final PortfolioClient portfolioClient;

    @Inject
    ExecutionHandlers(@External PortfolioClient portfolioClient) {
        this.portfolioClient = portfolioClient;
    }

    @RingloomHandler(templateId = TemplateIds.FILL)
    public int onFill(Fill fill) {
        return portfolioClient.executionReport(new ExecutionReport(
                fill.accountId(),
                fill.orderId(),
                fill.symbol(),
                fill.side(),
                ExecutionStatus.FILLED,
                fill.quantity(),
                fill.priceNanos()));
    }
}
