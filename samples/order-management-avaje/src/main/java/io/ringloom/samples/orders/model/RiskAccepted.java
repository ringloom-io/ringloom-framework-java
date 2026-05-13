// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.model;

public record RiskAccepted(
        long gatewaySequence,
        long acceptedNotionalNanos,
        long accountId,
        long orderId,
        Symbol symbol,
        Side side,
        TimeInForce timeInForce,
        long quantity,
        long priceNanos) {}
