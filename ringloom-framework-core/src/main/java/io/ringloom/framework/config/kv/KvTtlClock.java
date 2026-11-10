// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config.kv;

/** Clock a KV reader uses to evaluate entry expiry. */
public enum KvTtlClock {
    /** Reads the precise wall clock on every lookup of an expiring entry. */
    PRECISE,
    /** Uses a coarse cached clock; cheaper, expiry may be observed slightly late. */
    COARSE;

    /**
     * Returns the native reader option value ({@code 0} precise, {@code 1} coarse).
     *
     * @return the native option value
     */
    public int nativeValue() {
        return this == COARSE ? 1 : 0;
    }
}
