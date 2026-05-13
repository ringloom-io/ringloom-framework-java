// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.common;

import io.ringloom.samples.orders.model.Symbol;

/**
 * Precomputed reference data used on the message path without map lookups or allocation.
 */
public final class StaticTables {

    public static final int ACCOUNT_COUNT = 4;
    public static final int SYMBOL_COUNT = 4;

    public static final int[] ACCOUNTS = {1001, 1002, 1003, 1004};
    public static final long[] CREDIT_NANOS = {
        75_000_000_000_000L, 50_000_000_000_000L, 35_000_000_000_000L, 20_000_000_000_000L,
    };
    public static final Symbol[] SYMBOLS = {
        Symbol.AAPL, Symbol.MSFT, Symbol.NVDA, Symbol.JAVA,
    };
    public static final long[] BASE_PRICE_NANOS = {
        185_000_000_000L, 412_000_000_000L, 901_000_000_000L, 125_000_000_000L,
    };
    public static final long[] SYMBOL_NOTIONAL_LIMIT_NANOS = {
        15_000_000_000_000L, 15_000_000_000_000L, 12_000_000_000_000L, 8_000_000_000_000L,
    };
    public static final long MAX_PRICE_NANOS = 1_500_000_000_000L;

    private StaticTables() {}

    public static int accountIndex(long accountId) {
        for (int i = 0; i < ACCOUNTS.length; i++) {
            if (ACCOUNTS[i] == accountId) {
                return i;
            }
        }
        return -1;
    }

    public static int symbolIndex(Symbol symbol) {
        for (int i = 0; i < SYMBOLS.length; i++) {
            if (SYMBOLS[i] == symbol) {
                return i;
            }
        }
        return -1;
    }

    public static boolean validPrice(long priceNanos) {
        return priceNanos > 0 && priceNanos <= MAX_PRICE_NANOS;
    }

    public static long deterministicPrice(long sequence, Symbol symbol) {
        int idx = symbolIndex(symbol);
        long base = BASE_PRICE_NANOS[idx < 0 ? 0 : idx];
        return base + (sequence % 250L) * 1_000_000L;
    }
}
