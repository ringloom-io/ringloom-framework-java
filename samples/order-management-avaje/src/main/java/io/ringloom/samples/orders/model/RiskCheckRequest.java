// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.model;

public record RiskCheckRequest(
        long gatewaySequence,
        long accountId,
        long orderId,
        Symbol symbol,
        Side side,
        TimeInForce timeInForce,
        long quantity,
        long priceNanos) {}
