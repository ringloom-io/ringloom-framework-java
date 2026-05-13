// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.model;

public record NewOrder(
        long accountId,
        long orderId,
        Symbol symbol,
        Side side,
        TimeInForce timeInForce,
        long quantity,
        long priceNanos) {}
