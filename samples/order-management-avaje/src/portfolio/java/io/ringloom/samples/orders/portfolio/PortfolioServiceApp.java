// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.portfolio;

import io.avaje.inject.Component;
import io.ringloom.framework.RingloomApplicationRunner;
import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.ioc.avaje.AvajeRingloomBootstrap;
import io.ringloom.samples.orders.common.ServiceNames;
import io.ringloom.samples.orders.common.StaticTables;
import io.ringloom.samples.orders.model.ExecutionReport;
import io.ringloom.samples.orders.model.ExecutionStatus;
import io.ringloom.samples.orders.model.Side;
import io.ringloom.samples.orders.model.TemplateIds;
import java.nio.file.Path;

@RingloomApplication(service = ServiceNames.PORTFOLIO_SERVICE)
public final class PortfolioServiceApp {

    private static final Path DEFAULT_CONFIG = Path.of("samples/order-management-avaje/config/portfolio-service.yaml");

    public static void main(String[] args) throws Exception {
        try (RingloomApplicationRunner runner =
                AvajeRingloomBootstrap.fromYaml(DEFAULT_CONFIG).start()) {
            System.out.printf("%s ready; press Ctrl+C to stop%n", ServiceNames.PORTFOLIO_SERVICE);
            runner.awaitShutdown();
        }
    }
}

@Component
final class PortfolioHandlers {

    private final long[][] positions = new long[StaticTables.ACCOUNT_COUNT][StaticTables.SYMBOL_COUNT];
    private long updatesApplied;

    @RingloomHandler(templateId = TemplateIds.EXECUTION_REPORT)
    public void onExecutionReport(ExecutionReport report) {
        int accountIndex = StaticTables.accountIndex(report.accountId());
        int symbolIndex = StaticTables.symbolIndex(report.symbol());
        if (accountIndex < 0 || symbolIndex < 0 || report.status() != ExecutionStatus.FILLED) {
            return;
        }
        long signedQuantity = report.side() == Side.BUY ? report.quantity() : -report.quantity();
        positions[accountIndex][symbolIndex] += signedQuantity;
        updatesApplied++;
    }
}
