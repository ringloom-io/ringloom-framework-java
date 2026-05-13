// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.model;

public record OrderRejected(long accountId, long orderId, Symbol symbol, RejectReason reason, Stage stage) {}
