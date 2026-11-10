// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

/** TTL argument values for KV writes. Positive values are a TTL in milliseconds. */
public final class KvTtl {
    /** Use the store's configured default TTL. */
    public static final long STORE_DEFAULT = -1;
    /** Never expire. */
    public static final long NEVER = 0;

    private KvTtl() {}
}
