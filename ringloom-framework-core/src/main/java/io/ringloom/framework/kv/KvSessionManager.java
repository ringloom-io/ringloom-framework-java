// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import io.ringloom.service.KvReader;
import io.ringloom.service.KvSession;
import io.ringloom.service.KvSessionMode;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.agrona.concurrent.ManyToManyConcurrentArrayQueue;
import org.slf4j.Logger;

/**
 * Hands out native {@link KvSession}s ({@link KvSessionMode#SERIAL}) for one reader store.
 *
 * <p>Platform threads get a session bound to the thread through a {@link ThreadLocal}, up to
 * {@code maxThreadSessions} per store; the steady-state cost is one {@code ThreadLocal.get()}.
 * Virtual threads, and platform threads over the cap, borrow from a lazily filled pool of
 * {@code sessionPoolSize} sessions and spin, then park for 1 µs, while the pool is empty. A read never
 * holds a session across a blocking call, so waits stay short.
 *
 * <p>Sessions bound to threads that died stay open until the store closes (bounded by
 * {@code maxThreadSessions}). At most {@code maxThreadSessions + sessionPoolSize} native reader slots
 * are used per store.
 */
final class KvSessionManager {
    private static final int SPINS_BEFORE_PARK = 64;
    private static final long PARK_NANOS = 1_000;

    private final KvReader reader;
    private final int maxThreadSessions;
    private final int poolSize;
    private final ThreadLocal<Lease> bound = ThreadLocal.withInitial(() -> new Lease(false));
    private final ManyToManyConcurrentArrayQueue<Lease> pool;
    private final Object lock = new Object();
    private final ArrayList<KvSession> all = new ArrayList<>();
    private int boundCount;
    private volatile int pooledCount;
    private volatile boolean closed;

    /** A session plus a reusable length holder; pooled leases return to the pool after each read. */
    static final class Lease {
        final boolean pooled;
        final long[] length = new long[1];
        KvSession session;
        boolean overCap;

        Lease(boolean pooled) {
            this.pooled = pooled;
        }
    }

    KvSessionManager(KvReader reader, int maxThreadSessions, int poolSize) {
        this.reader = reader;
        this.maxThreadSessions = maxThreadSessions;
        this.poolSize = poolSize;
        this.pool = new ManyToManyConcurrentArrayQueue<>(poolSize);
    }

    /**
     * Acquires a lease for the calling thread, or {@code null} when the manager is closed. Pooled
     * leases must be handed back with {@link #release(Lease)}.
     */
    Lease acquire() {
        if (!Thread.currentThread().isVirtual()) {
            Lease lease = bound.get();
            if (lease.session != null) {
                return lease;
            }
            if (!lease.overCap && bindSession(lease)) {
                return lease;
            }
        }
        return borrow();
    }

    void release(Lease lease) {
        if (lease.pooled) {
            pool.offer(lease);
        }
    }

    private boolean bindSession(Lease lease) {
        synchronized (lock) {
            if (closed) {
                return false;
            }
            if (boundCount >= maxThreadSessions) {
                lease.overCap = true;
                return false;
            }
            KvSession session = reader.newSession(KvSessionMode.SERIAL);
            all.add(session);
            boundCount++;
            lease.session = session;
            return true;
        }
    }

    private Lease borrow() {
        int spins = 0;
        while (true) {
            Lease lease = pool.poll();
            if (lease != null) {
                return lease;
            }
            if (closed) {
                return null;
            }
            if (pooledCount < poolSize) {
                synchronized (lock) {
                    if (closed) {
                        return null;
                    }
                    if (pooledCount < poolSize) {
                        Lease created = new Lease(true);
                        created.session = reader.newSession(KvSessionMode.SERIAL);
                        all.add(created.session);
                        pooledCount = pooledCount + 1;
                        return created;
                    }
                }
            }
            if (spins < SPINS_BEFORE_PARK) {
                spins++;
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(PARK_NANOS);
            }
        }
    }

    /** Returns the number of native sessions created (bound + pooled) and not yet closed. */
    int sessionCount() {
        synchronized (lock) {
            return all.size();
        }
    }

    /**
     * Closes every bound and pooled session. Sessions in use are retried until the timeout, then
     * skipped with a WARN.
     *
     * @return the number of sessions that could not be closed
     */
    int close(long timeoutMillis, String store, Logger logger) {
        KvSession[] sessions;
        synchronized (lock) {
            closed = true;
            sessions = all.toArray(new KvSession[0]);
            all.clear();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        int leaked = 0;
        for (KvSession session : sessions) {
            while (true) {
                try {
                    session.close();
                    break;
                } catch (IllegalStateException | WrongThreadException ex) {
                    if (System.nanoTime() - deadline >= 0) {
                        leaked++;
                        if (logger != null) {
                            logger.warn(
                                    "kv store '{}': session still in use after {} ms; not closed",
                                    store,
                                    timeoutMillis);
                        }
                        break;
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
            }
        }
        return leaked;
    }
}
