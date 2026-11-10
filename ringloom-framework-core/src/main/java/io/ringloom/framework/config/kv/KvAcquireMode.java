// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config.kv;

/** How a KV writer acquires the store's writer lease at open. */
public enum KvAcquireMode {
    /** Fail the open (and runtime startup) when another client holds the lease. */
    FAIL_IF_HELD,
    /** Queue as a standby writer; the store opens in {@code QUEUED} state and is granted later. */
    WAIT;

    /**
     * Returns the native acquire option value ({@code 0} fail if held, {@code 1} wait).
     *
     * @return the native option value
     */
    public int nativeValue() {
        return this == WAIT ? 1 : 0;
    }
}
