// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.model;

public record ExecutionReport(
        long accountId,
        long orderId,
        Symbol symbol,
        Side side,
        ExecutionStatus status,
        long quantity,
        long priceNanos) {}
