// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.pricing.model;

public record PriceQuote(String symbol, long bidNanos, long askNanos, long quoteId) {}
