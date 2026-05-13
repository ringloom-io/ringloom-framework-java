// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.model;

public record Fill(
        long accountId, long orderId, Symbol symbol, Side side, long quantity, long priceNanos, long gatewaySequence) {}
