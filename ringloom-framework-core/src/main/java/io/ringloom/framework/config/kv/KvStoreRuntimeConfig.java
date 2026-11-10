// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config.kv;

/**
 * Per-store runtime settings for a KV store ({@code ringloom.kv.stores.<name>}).
 *
 * @param reader                 open a reader even without generated read methods (raw API)
 * @param writer                 open a writer even without generated write methods (raw API)
 * @param required               readers: block startup until ready (default {@code false})
 * @param waitReadyTimeoutMillis required readers: startup wait (default 5000)
 * @param prefault               prefault the reader map (default {@code false})
 * @param ttlClock               reader expiry clock (default {@link KvTtlClock#PRECISE})
 * @param maxThreadSessions      sessions bound to platform threads per store (default 64)
 * @param sessionPoolSize        pooled sessions for virtual threads and overflow (default {@code max(4, cores)})
 * @param acquire                writer lease acquisition (default {@link KvAcquireMode#FAIL_IF_HELD})
 * @param openTimeoutMillis      writer open timeout (default 5000)
 * @param maxFrameLength         writer frame budget; {@code 0} = native default
 * @param maxPendingAcks         writer ack registry capacity (default 1024)
 * @param ackTimeoutMillis       writer ack timeout; {@code 0} = no timeout (default)
 * @param config                 optional writer store-config overrides
 */
public record KvStoreRuntimeConfig(
        boolean reader,
        boolean writer,
        boolean required,
        long waitReadyTimeoutMillis,
        boolean prefault,
        KvTtlClock ttlClock,
        int maxThreadSessions,
        int sessionPoolSize,
        KvAcquireMode acquire,
        long openTimeoutMillis,
        long maxFrameLength,
        int maxPendingAcks,
        long ackTimeoutMillis,
        KvStoreConfigOverride config) {

    /** Default {@link #waitReadyTimeoutMillis()}. */
    public static final long DEFAULT_WAIT_READY_TIMEOUT_MILLIS = 5000;
    /** Default {@link #maxThreadSessions()}. */
    public static final int DEFAULT_MAX_THREAD_SESSIONS = 64;
    /** Default {@link #openTimeoutMillis()}. */
    public static final long DEFAULT_OPEN_TIMEOUT_MILLIS = 5000;
    /** Default {@link #maxPendingAcks()}. */
    public static final int DEFAULT_MAX_PENDING_ACKS = 1024;

    private static final long U32_MAX = 0xffffffffL;

    public KvStoreRuntimeConfig {
        ttlClock = ttlClock == null ? KvTtlClock.PRECISE : ttlClock;
        acquire = acquire == null ? KvAcquireMode.FAIL_IF_HELD : acquire;
        config = config == null ? KvStoreConfigOverride.none() : config;
        if (waitReadyTimeoutMillis < 0 || waitReadyTimeoutMillis > U32_MAX) {
            throw new IllegalArgumentException("kv store waitReadyTimeoutMillis must be in 0..4294967295");
        }
        if (maxThreadSessions < 0) {
            throw new IllegalArgumentException("kv store maxThreadSessions must be >= 0");
        }
        if (sessionPoolSize < 1) {
            throw new IllegalArgumentException("kv store sessionPoolSize must be >= 1");
        }
        if (openTimeoutMillis < 0 || openTimeoutMillis > U32_MAX) {
            throw new IllegalArgumentException("kv store openTimeoutMillis must be in 0..4294967295");
        }
        if (maxFrameLength < 0 || maxFrameLength > U32_MAX) {
            throw new IllegalArgumentException("kv store maxFrameLength must be in 0..4294967295");
        }
        if (maxPendingAcks < 1) {
            throw new IllegalArgumentException("kv store maxPendingAcks must be >= 1");
        }
        if (ackTimeoutMillis < 0) {
            throw new IllegalArgumentException("kv store ackTimeoutMillis must be >= 0");
        }
    }

    /**
     * Returns the default settings (neither raw reader nor raw writer requested).
     *
     * @return the default per-store settings
     */
    public static KvStoreRuntimeConfig defaults() {
        return new KvStoreRuntimeConfig(
                false,
                false,
                false,
                DEFAULT_WAIT_READY_TIMEOUT_MILLIS,
                false,
                KvTtlClock.PRECISE,
                DEFAULT_MAX_THREAD_SESSIONS,
                defaultSessionPoolSize(),
                KvAcquireMode.FAIL_IF_HELD,
                DEFAULT_OPEN_TIMEOUT_MILLIS,
                0,
                DEFAULT_MAX_PENDING_ACKS,
                0,
                KvStoreConfigOverride.none());
    }

    /**
     * Returns the default session pool size, {@code max(4, availableProcessors)}.
     *
     * @return the default pool size
     */
    public static int defaultSessionPoolSize() {
        return Math.max(4, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Returns a copy with the raw reader and writer flags replaced.
     *
     * @param reader whether a raw reader is requested
     * @param writer whether a raw writer is requested
     * @return the updated settings
     */
    public KvStoreRuntimeConfig withRoles(boolean reader, boolean writer) {
        return new KvStoreRuntimeConfig(
                reader,
                writer,
                required,
                waitReadyTimeoutMillis,
                prefault,
                ttlClock,
                maxThreadSessions,
                sessionPoolSize,
                acquire,
                openTimeoutMillis,
                maxFrameLength,
                maxPendingAcks,
                ackTimeoutMillis,
                config);
    }
}
