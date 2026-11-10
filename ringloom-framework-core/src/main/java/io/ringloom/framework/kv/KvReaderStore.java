// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import io.ringloom.framework.config.kv.KvStoreRuntimeConfig;
import io.ringloom.framework.status.RingloomHandlerStatus;
import io.ringloom.service.KvRawVisitor;
import io.ringloom.service.KvReader;
import io.ringloom.service.KvStatus;
import io.ringloom.service.KvStatusHolder;
import io.ringloom.service.KvStoreConfig;
import io.ringloom.service.KvVisitor;
import io.ringloom.service.RingloomStatus;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;

/**
 * Framework-managed reader handle for one KV store (raw segment API).
 *
 * <p>Reads are safe from any thread: framework platform threads, virtual threads and application
 * threads. Each call acquires a native session (thread-bound on platform threads, pooled otherwise)
 * and releases it before returning. While the store is {@link KvStoreState#PENDING} or
 * {@link KvStoreState#FAILED} every read returns {@link RingloomStatus#NOT_READY} immediately; after
 * runtime shutdown reads return {@link RingloomHandlerStatus#SHUTDOWN}. Steady-state reads with
 * native segments allocate nothing.
 */
public final class KvReaderStore {
    private static final MemorySegment EMPTY_DESTINATION = Arena.global().allocate(8);

    private final String name;
    private final KvStoreRuntimeConfig settings;
    private final List<CompletableFuture<KvReaderStore>> waiters = new ArrayList<>();
    /** While set (during {@code KvRuntime.start()}), publishing does not complete waiters. */
    private volatile boolean waitersHeld;

    private volatile KvStoreState state = KvStoreState.PENDING;
    private volatile KvSessionManager sessions;
    private volatile KvReader reader;
    private volatile KvStoreConfig config;
    private volatile int nativeState = -1;
    private final java.util.concurrent.atomic.AtomicLong decodeErrors = new java.util.concurrent.atomic.AtomicLong();

    // Control-thread state.
    private KvStatusHolder statusHolder;
    private volatile KvStoreMetrics.Reader metrics;
    private long nextRetryNanos;
    private int failedAttempts;

    KvReaderStore(String name, KvStoreRuntimeConfig settings) {
        this.name = name;
        this.settings = settings;
    }

    /** Returns the store name. */
    public String name() {
        return name;
    }

    /** Returns the framework state of this store. */
    public KvStoreState state() {
        return state;
    }

    /**
     * Returns whether the store is {@link KvStoreState#OPEN} and the native reader is past
     * initialization (as of the last control-thread status refresh).
     *
     * @return {@code true} when reads can be served
     */
    public boolean isReady() {
        int s = nativeState;
        return state == KvStoreState.OPEN && s > 0 && s != 4;
    }

    /**
     * Returns the effective store config captured at open, or {@code null} while pending.
     *
     * @return the store config, or {@code null}
     */
    public KvStoreConfig config() {
        return config;
    }

    /**
     * Copying get into {@code destination[destinationOffset, +capacity)}.
     *
     * @return {@code OK}, {@code KV_MISSING}, {@code KV_BUFFER_TOO_SMALL} ({@code outLength[0]} holds
     *     the required length), {@code NOT_READY}, or {@code SHUTDOWN}
     */
    public int get(
            MemorySegment key,
            long keyOffset,
            long keyLength,
            MemorySegment destination,
            long destinationOffset,
            long capacity,
            long[] outLength) {
        KvStoreState st = state;
        if (st != KvStoreState.OPEN) {
            return notOpenStatus(st);
        }
        KvSessionManager manager = sessions;
        KvSessionManager.Lease lease = null;
        try {
            lease = manager.acquire();
            if (lease == null) {
                return RingloomHandlerStatus.SHUTDOWN;
            }
            return lease.session.get(key, keyOffset, keyLength, destination, destinationOffset, capacity, outLength);
        } catch (IllegalStateException ex) {
            return closedOrRethrow(ex);
        } finally {
            if (lease != null) {
                manager.release(lease);
            }
        }
    }

    /**
     * Tests whether a key is present (any length, including zero). Never copies the value.
     *
     * @return {@code OK} when present, {@code KV_MISSING}, {@code NOT_READY}, or {@code SHUTDOWN}
     */
    public int contains(MemorySegment key, long keyOffset, long keyLength) {
        KvStoreState st = state;
        if (st != KvStoreState.OPEN) {
            return notOpenStatus(st);
        }
        KvSessionManager manager = sessions;
        KvSessionManager.Lease lease = null;
        try {
            lease = manager.acquire();
            if (lease == null) {
                return RingloomHandlerStatus.SHUTDOWN;
            }
            int status = lease.session.get(key, keyOffset, keyLength, EMPTY_DESTINATION, 0, 0, lease.length);
            return status == RingloomStatus.KV_BUFFER_TOO_SMALL ? RingloomStatus.OK : status;
        } catch (IllegalStateException ex) {
            return closedOrRethrow(ex);
        } finally {
            if (lease != null) {
                manager.release(lease);
            }
        }
    }

    /**
     * Visits every live entry of one consistent snapshot. Segments are borrowed and valid only during
     * the callback; the visitor must not call KV APIs. Visitor exceptions are rethrown wrapped in
     * {@link IllegalStateException}.
     *
     * @return {@code OK}, {@code NOT_READY}, {@code SHUTDOWN}, or a native error
     */
    public int forEach(KvVisitor visitor) {
        KvStoreState st = state;
        if (st != KvStoreState.OPEN) {
            return notOpenStatus(st);
        }
        KvSessionManager manager = sessions;
        KvSessionManager.Lease lease = null;
        try {
            lease = manager.acquire();
            if (lease == null) {
                return RingloomHandlerStatus.SHUTDOWN;
            }
            return lease.session.forEach(visitor);
        } catch (IllegalStateException ex) {
            return closedOrRethrow(ex);
        } finally {
            if (lease != null) {
                manager.release(lease);
            }
        }
    }

    /**
     * Allocation-free iteration over raw addresses valid only during the callback.
     *
     * @return {@code OK}, {@code NOT_READY}, {@code SHUTDOWN}, or a native error
     */
    public int forEachRaw(KvRawVisitor visitor) {
        KvStoreState st = state;
        if (st != KvStoreState.OPEN) {
            return notOpenStatus(st);
        }
        KvSessionManager manager = sessions;
        KvSessionManager.Lease lease = null;
        try {
            lease = manager.acquire();
            if (lease == null) {
                return RingloomHandlerStatus.SHUTDOWN;
            }
            return lease.session.forEachRaw(visitor);
        } catch (IllegalStateException ex) {
            return closedOrRethrow(ex);
        } finally {
            if (lease != null) {
                manager.release(lease);
            }
        }
    }

    /**
     * Allocation-free native status read into a caller-owned holder (updated only on {@code OK}).
     *
     * @param out the reusable holder
     * @return the native status, {@code NOT_READY} while not open, or {@code SHUTDOWN}
     */
    public int status(KvStatusHolder out) {
        KvStoreState st = state;
        if (st != KvStoreState.OPEN) {
            return notOpenStatus(st);
        }
        try {
            return reader.status(out);
        } catch (IllegalStateException ex) {
            return closedOrRethrow(ex);
        }
    }

    /**
     * Returns the number of values generated proxies failed to decode.
     *
     * @return the decode error count
     */
    public long decodeErrors() {
        return decodeErrors.get();
    }

    void recordDecodeError() {
        decodeErrors.incrementAndGet();
    }

    private static int notOpenStatus(KvStoreState st) {
        return st == KvStoreState.CLOSED ? RingloomHandlerStatus.SHUTDOWN : RingloomStatus.NOT_READY;
    }

    private int closedOrRethrow(IllegalStateException ex) {
        if (state == KvStoreState.CLOSED) {
            return RingloomHandlerStatus.SHUTDOWN;
        }
        throw ex;
    }

    // ---- runtime-internal ------------------------------------------------------------------------

    KvStoreRuntimeConfig settings() {
        return settings;
    }

    /** Native reader status snapshot (allocates); {@code null} when not open. */
    KvStatus nativeStatus() {
        KvReader r = reader;
        if (state != KvStoreState.OPEN || r == null) {
            return null;
        }
        try {
            return r.status();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    int sessionCount() {
        KvSessionManager manager = sessions;
        return manager == null ? 0 : manager.sessionCount();
    }

    /** Publishes an opened native reader; the volatile state write comes last. */
    void publish(KvReader opened) {
        KvStoreConfig effective = opened.config();
        KvSessionManager manager =
                new KvSessionManager(opened, settings.maxThreadSessions(), settings.sessionPoolSize());
        statusHolder = new KvStatusHolder();
        if (opened.status(statusHolder) == RingloomStatus.OK) {
            nativeState = statusHolder.state();
            KvStoreMetrics.Reader m = metrics;
            if (m != null) {
                m.updateOpen(statusHolder, manager.sessionCount(), decodeErrors.get());
            }
        }
        config = effective;
        sessions = manager;
        reader = opened;
        state = KvStoreState.OPEN;
        if (!waitersHeld) {
            completeWaiters(null);
        }
    }

    /** Defers waiter completion on publish until {@link #releaseWaiters()}. */
    void holdWaiters() {
        waitersHeld = true;
    }

    /** Stops deferring and completes the waiters if the store opened while they were held. */
    void releaseWaiters() {
        waitersHeld = false;
        if (state == KvStoreState.OPEN) {
            completeWaiters(null);
        }
    }

    void markPending(long nextRetryNanos) {
        this.nextRetryNanos = nextRetryNanos;
        failedAttempts++;
    }

    int failedAttempts() {
        return failedAttempts;
    }

    long nextRetryNanos() {
        return nextRetryNanos;
    }

    void fail(Throwable cause) {
        state = KvStoreState.FAILED;
        completeWaiters(cause);
    }

    /** Adds a future completed when the store opens (or fails/closes). */
    void addWaiter(CompletableFuture<KvReaderStore> future) {
        KvStoreState st;
        synchronized (waiters) {
            st = state;
            if (st == KvStoreState.PENDING) {
                waiters.add(future);
                return;
            }
        }
        if (st == KvStoreState.OPEN) {
            future.complete(this);
        } else {
            future.completeExceptionally(new IllegalStateException("kv store '" + name + "' is " + st));
        }
    }

    private void completeWaiters(Throwable cause) {
        List<CompletableFuture<KvReaderStore>> copy;
        synchronized (waiters) {
            if (waiters.isEmpty()) {
                return;
            }
            copy = new ArrayList<>(waiters);
            waiters.clear();
        }
        for (CompletableFuture<KvReaderStore> future : copy) {
            if (cause == null) {
                future.complete(this);
            } else {
                future.completeExceptionally(cause);
            }
        }
    }

    /** Attaches the store's metric handles (once; later calls are ignored). */
    void attachMetrics(KvStoreMetrics.Reader readerMetrics) {
        if (metrics == null) {
            metrics = readerMetrics;
        }
    }

    /**
     * Control thread: refreshes the cached native state and the reader metrics. Allocation-free.
     * Returns work done.
     */
    int refreshStatus() {
        KvReader r = reader;
        KvStoreState st = state;
        KvStoreMetrics.Reader m = metrics;
        if (st != KvStoreState.OPEN || r == null) {
            if (m != null) {
                m.updateNotOpen(
                        st == KvStoreState.OPEN ? KvStoreState.PENDING : st, sessionCount(), decodeErrors.get());
            }
            return 0;
        }
        if (r.status(statusHolder) == RingloomStatus.OK) {
            nativeState = statusHolder.state();
            if (m != null) {
                m.updateOpen(statusHolder, sessionCount(), decodeErrors.get());
            }
        }
        return 1;
    }

    /** Marks the store closed so hot-path reads return {@code SHUTDOWN}. */
    void markClosing() {
        state = KvStoreState.CLOSED;
    }

    /** Closes sessions and the native reader. */
    void close(long closeTimeoutMillis, Logger logger) {
        state = KvStoreState.CLOSED;
        completeWaiters(new IllegalStateException("kv runtime closed"));
        KvSessionManager manager = sessions;
        KvReader r = reader;
        if (manager == null || r == null) {
            return;
        }
        int leaked = manager.close(closeTimeoutMillis, name, logger);
        if (leaked > 0) {
            if (logger != null) {
                logger.error("kv store '{}': {} reader session(s) leaked; native reader not closed", name, leaked);
            }
            return;
        }
        try {
            r.close();
        } catch (RuntimeException ex) {
            if (logger != null) {
                logger.error("kv store '{}': failed to close native reader", name, ex);
            }
        }
    }
}
