// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.execution;

import io.ringloom.framework.RingloomApplicationRunner;
import io.ringloom.framework.RingloomBootstrap;
import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.annotation.RingloomServiceComponent;
import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.samples.orders.common.ServiceNames;
import io.ringloom.samples.orders.sbe.ExecutionReportDto;
import io.ringloom.samples.orders.sbe.ExecutionReportEncoder;
import io.ringloom.samples.orders.sbe.ExecutionStatus;
import io.ringloom.samples.orders.sbe.FillDecoder;
import java.nio.file.Path;

@RingloomApplication(service = ServiceNames.EXECUTION_SERVICE)
public final class ExecutionServiceApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/order-management/config/execution-service.yaml");

    public static void main(String[] args) throws Exception {
        try (RingloomApplicationRunner app =
                RingloomBootstrap.fromYaml(DEFAULT_CONFIG).start()) {
            System.out.printf("%s ready; press Ctrl+C to stop%n", ServiceNames.EXECUTION_SERVICE);
            app.awaitShutdown();
        }
    }
}

@RingloomClient(service = ServiceNames.PORTFOLIO_SERVICE)
interface PortfolioClient {
    @RingloomRequest(templateId = ExecutionReportEncoder.TEMPLATE_ID)
    int executionReport(ExecutionReportDto payload);
}

@RingloomServiceComponent
final class ExecutionHandlers {

    private final ExecutionReportDto report = new ExecutionReportDto();
    private PortfolioClient portfolioClient;

    @RingloomHandler(templateId = FillDecoder.TEMPLATE_ID)
    public int onFill(FillDecoder fill, MessageContext context) {
        report.accountId(fill.accountId());
        report.orderId(fill.orderId());
        report.symbol(fill.symbol());
        report.side(fill.side());
        report.status(ExecutionStatus.FILLED);
        report.quantity(fill.quantity());
        report.priceNanos(fill.priceNanos());
        return portfolioClient(context).executionReport(report);
    }

    private PortfolioClient portfolioClient(MessageContext context) {
        if (portfolioClient == null) {
            portfolioClient = context.runtime().generatedClient(PortfolioClient.class);
        }
        return portfolioClient;
    }
}
