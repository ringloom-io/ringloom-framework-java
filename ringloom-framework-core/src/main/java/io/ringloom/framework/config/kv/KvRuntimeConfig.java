// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config.kv;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Runtime configuration for replicated KV stores ({@code ringloom.kv}).
 *
 * <p>When {@code enabled == false} (the default) the runtime creates no KV objects and touches no
 * native KV API.
 *
 * @param enabled              whether KV stores are enabled
 * @param statusIntervalMillis reader status refresh interval on the control thread (default 1000)
 * @param openRetryMillis      retry interval for pending readers (default 1000)
 * @param closeTimeoutMillis   how long close waits for in-use reader sessions (default 1000)
 * @param stores               per-store settings keyed by store name
 */
public record KvRuntimeConfig(
        boolean enabled,
        long statusIntervalMillis,
        long openRetryMillis,
        long closeTimeoutMillis,
        Map<String, KvStoreRuntimeConfig> stores) {

    /** Default {@link #statusIntervalMillis()}. */
    public static final long DEFAULT_STATUS_INTERVAL_MILLIS = 1000;
    /** Default {@link #openRetryMillis()}. */
    public static final long DEFAULT_OPEN_RETRY_MILLIS = 1000;
    /** Default {@link #closeTimeoutMillis()}. */
    public static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 1000;

    public KvRuntimeConfig {
        stores = stores == null ? Map.of() : Map.copyOf(stores);
        if (statusIntervalMillis <= 0) {
            throw new IllegalArgumentException("kv.statusIntervalMillis must be > 0");
        }
        if (openRetryMillis <= 0) {
            throw new IllegalArgumentException("kv.openRetryMillis must be > 0");
        }
        if (closeTimeoutMillis <= 0) {
            throw new IllegalArgumentException("kv.closeTimeoutMillis must be > 0");
        }
        for (String name : stores.keySet()) {
            if (!isValidStoreName(name)) {
                throw new IllegalArgumentException(
                        "kv.stores." + name + ": invalid store name (1..64 bytes of [A-Za-z0-9._-])");
            }
        }
    }

    /**
     * Returns the disabled default configuration.
     *
     * @return the disabled configuration
     */
    public static KvRuntimeConfig disabled() {
        return new KvRuntimeConfig(
                false,
                DEFAULT_STATUS_INTERVAL_MILLIS,
                DEFAULT_OPEN_RETRY_MILLIS,
                DEFAULT_CLOSE_TIMEOUT_MILLIS,
                Map.of());
    }

    /**
     * Returns an enabled configuration with defaults and no per-store settings.
     *
     * @return the enabled configuration
     */
    public static KvRuntimeConfig enabledDefaults() {
        return enabled(Map.of());
    }

    /**
     * Returns an enabled configuration with defaults and the given per-store settings.
     *
     * @param stores per-store settings
     * @return the enabled configuration
     */
    public static KvRuntimeConfig enabled(Map<String, KvStoreRuntimeConfig> stores) {
        return new KvRuntimeConfig(
                true, DEFAULT_STATUS_INTERVAL_MILLIS, DEFAULT_OPEN_RETRY_MILLIS, DEFAULT_CLOSE_TIMEOUT_MILLIS, stores);
    }

    /**
     * Returns the configured settings for a store, or {@link KvStoreRuntimeConfig#defaults()}.
     *
     * @param store the store name
     * @return the effective per-store settings
     */
    public KvStoreRuntimeConfig store(String store) {
        KvStoreRuntimeConfig settings = stores.get(store);
        return settings == null ? KvStoreRuntimeConfig.defaults() : settings;
    }

    /**
     * Returns whether a store name is valid: 1..64 bytes of {@code [A-Za-z0-9._-]}.
     *
     * @param name the store name
     * @return {@code true} when valid
     */
    public static boolean isValidStoreName(String name) {
        if (name == null) {
            return false;
        }
        int length = name.getBytes(StandardCharsets.UTF_8).length;
        if (length < 1 || length > 64) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '_'
                    || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
