// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.marketdata.common;

/**
 * Allocation-free instrument reference data for the market-data sample.
 */
public final class Instruments {
    public static final int COUNT = 4;
    public static final int[] IDS = {101, 102, 103, 104};
    public static final String[] NAMES = {"AAPL", "MSFT", "NVDA", "EURUSD"};
    public static final long[] MID_NANOS = {
        189_420_000_000L, 427_130_000_000L, 924_610_000_000L, 1_086_500_000L,
    };

    private Instruments() {}

    public static int index(long instrumentId) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i] == instrumentId) {
                return i;
            }
        }
        return -1;
    }

    public static String name(long instrumentId) {
        int index = index(instrumentId);
        return index < 0 ? "UNKNOWN" : NAMES[index];
    }
}
