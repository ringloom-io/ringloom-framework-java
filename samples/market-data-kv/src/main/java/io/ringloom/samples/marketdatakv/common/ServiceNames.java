// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.marketdatakv.common;

/**
 * Service and store names used by the replicated KV market-data sample.
 */
public final class ServiceNames {
    public static final String PRICE_WRITER = "price-writer";
    public static final String PRICE_READER = "price-reader";
    public static final String PRICE_TERMINAL = "price-terminal";

    /** The replicated KV store holding one {@code PriceSnapshot} per instrument id. */
    public static final String PRICES_STORE = "prices";

    private ServiceNames() {}
}
