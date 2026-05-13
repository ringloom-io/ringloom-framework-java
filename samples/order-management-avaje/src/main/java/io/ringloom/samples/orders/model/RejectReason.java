// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.model;

public enum RejectReason {
    UNKNOWN_SYMBOL,
    ZERO_QUANTITY,
    BAD_PRICE,
    RISK_CREDIT,
    RISK_SYMBOL_LIMIT,
    SYSTEM_BUSY
}
