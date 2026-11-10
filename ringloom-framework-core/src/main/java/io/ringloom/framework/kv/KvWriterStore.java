// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import io.ringloom.framework.config.kv.KvStoreRuntimeConfig;
import io.ringloom.framework.metrics.RingloomMetrics;
import io.ringloom.framework.status.RingloomHandlerStatus;
import io.ringloom.framework.topic.ack.AckCallback;
import io.ringloom.framework.topic.ack.AckFeedbackPoller;
import io.ringloom.framework.topic.ack.AckStatus;
import io.ringloom.framework.topic.ack.TopicAckRegistry;
import io.ringloom.service.KvStoreConfig;
import io.ringloom.service.RingloomStatus;
import io.ringloom.service.TopicAckMode;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Framework-managed writer handle for one KV store (raw segment API).
 *
 * <p>Statuses are the native ones ({@code OK}, {@code BACKPRESSURE}, {@code KV_NOT_WRITER},
 * {@code TOPIC_LEADER_UNAVAILABLE}, {@code KV_KEY_TOO_LARGE}, {@code KV_VALUE_TOO_LARGE},
 * {@code KV_BATCH_FULL}) plus {@link RingloomHandlerStatus#SHUTDOWN} after close. The framework does
 * not gate writes on {@link #state()}; native code rejects writes while queued, revoked or
 * reasserting.
 *
 * <p><strong>Threading and batches:</strong> writes from several threads are serialized by a
 * per-store {@link ReentrantLock}. {@link #beginBatch()} acquires it and keeps it until
 * {@link #commitBatch()}/{@link #commitBatchAck} returns {@code OK} or {@link #abandonBatch()} is
 * called, so mutations from other threads block instead of silently joining someone else's batch.
 * Inside a batch, writes return {@code OK} with no sequence; ack callbacks can only be attached to
 * {@link #commitBatchAck}, and that ack covers the frames published by that commit only (frames
 * auto-flushed earlier because the batch buffer filled up are not individually acknowledged). If a
 * commit returns non-{@code OK} (for example {@code BACKPRESSURE}) the batch stays buffered and the
 * caller keeps the lock: retry the commit or call {@link #abandonBatch()}.
 *
 * <p><strong>Acks:</strong> {@code *Ack} methods publish with {@code replicate_once} and complete the
 * callback on the control thread with {@link AckStatus#ACKED}, {@link AckStatus#LEADER_CHANGED},
 * {@link AckStatus#REVOKED}, {@link AckStatus#ACK_TIMEOUT}, or {@link AckStatus#SHUTDOWN}. If the
 * write is accepted but cannot be tracked (the pending-ack pool is full or the runtime is shutting
 * down), the callback instead completes with {@link AckStatus#SHUTDOWN} on the writing thread,
 * after the writer lock is released and any batch is closed.
 */
public final class KvWriterStore {
    private static final int RING_SIZE = 16;
    private static final int RING_MASK = RING_SIZE - 1;

    private final String name;
    private final KvStoreRuntimeConfig settings;
    private volatile KvStoreConfig requestedConfig;
    private final TopicAckRegistry acks;
    private final long ackTimeoutNanos;
    private final Logger logger;
    private final Consumer<KvWriterStateEvent> stateDispatcher;
    private final ReentrantLock batchLock = new ReentrantLock();
    private final long[] sequence = new long[1];
    private boolean inBatch;

    private volatile KvWriterPort port;
    private volatile KvStoreConfig config;
    private volatile KvWriterState state = KvWriterState.QUEUED;
    private volatile long writerEpoch;
    private volatile boolean closed;

    // Single-producer transition ring: written by the native state handler (control thread or a
    // startup open), drained by the control thread.
    private final int[] ringStates = new int[RING_SIZE];
    private final long[] ringEpochs = new long[RING_SIZE];
    private volatile long ringWrite;
    private volatile long ringRead;
    private volatile boolean ringOverflowed;

    // Control-thread state.
    private KvWriterState dispatchedState;
    private long dispatchedEpoch = -1;
    private long transitions;
    private final KvStoreMetrics.Writer metrics;

    KvWriterStore(
            String name,
            KvStoreConfig requestedConfig,
            KvStoreRuntimeConfig settings,
            RingloomMetrics metrics,
            Logger logger,
            Consumer<KvWriterStateEvent> stateDispatcher) {
        this.name = name;
        this.requestedConfig = requestedConfig;
        this.settings = settings;
        this.logger = logger;
        this.stateDispatcher = stateDispatcher;
        this.acks = new TopicAckRegistry(settings.maxPendingAcks(), KvStoreMetrics.ackMetrics(metrics, name), logger);
        this.metrics = new KvStoreMetrics.Writer(metrics, name);
        this.ackTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(settings.ackTimeoutMillis());
    }

    /** Returns the store name. */
    public String name() {
        return name;
    }

    /**
     * Returns the effective store config the broker accepted, or {@code null} before open.
     *
     * @return the store config
     */
    public KvStoreConfig config() {
        return config;
    }

    /**
     * Returns the lease state as of the last control-thread drain. Informational only.
     *
     * @return the writer state
     */
    public KvWriterState state() {
        return state;
    }

    /**
     * Returns the lease epoch as of the last control-thread drain ({@code 0} when not granted; kept
     * while reasserting).
     *
     * @return the writer epoch
     */
    public long writerEpoch() {
        return writerEpoch;
    }

    /**
     * Fire-and-forget put.
     *
     * @param ttlMillis {@link KvTtl#STORE_DEFAULT}, {@link KvTtl#NEVER}, or a positive TTL
     * @return the native status, or {@code SHUTDOWN}
     */
    public int put(
            MemorySegment key,
            long keyOffset,
            long keyLength,
            MemorySegment value,
            long valueOffset,
            long valueLength,
            long ttlMillis) {
        KvWriterPort p = port;
        if (p == null || closed) {
            return unavailableStatus();
        }
        batchLock.lock();
        try {
            return p.put(
                    key,
                    keyOffset,
                    keyLength,
                    value,
                    valueOffset,
                    valueLength,
                    ttlMillis,
                    TopicAckMode.FIRE_AND_FORGET,
                    sequence);
        } catch (IllegalStateException ex) {
            return closedOrRethrow(ex);
        } finally {
            batchLock.unlock();
        }
    }

    /**
     * {@code replicate_once} put; the callback completes on the control thread. Not allowed inside a
     * batch (use {@link #commitBatchAck}).
     *
     * @return the native status, or {@code SHUTDOWN}; the callback is registered only on {@code OK}
     */
    public int putAck(
            MemorySegment key,
            long keyOffset,
            long keyLength,
            MemorySegment value,
            long valueOffset,
            long valueLength,
            long ttlMillis,
            AckCallback callback,
            Object context) {
        KvWriterPort p = port;
        if (p == null || closed) {
            return unavailableStatus();
        }
        int status;
        long untracked = 0;
        batchLock.lock();
        try {
            rejectAckInBatch();
            status = p.put(
                    key,
                    keyOffset,
                    keyLength,
                    value,
                    valueOffset,
                    valueLength,
                    ttlMillis,
                    TopicAckMode.REPLICATE_ONCE,
                    sequence);
            if (status == RingloomStatus.OK && !track(p, sequence[0], callback, context)) {
                untracked = sequence[0];
            }
        } catch (IllegalStateException ex) {
            return closedOrRethrow(ex);
        } finally {
            batchLock.unlock();
        }
        completeUntracked(untracked, callback, context);
        return status;
    }

    /** Fire-and-forget delete. */
    public int delete(MemorySegment key, long keyOffset, long keyLength) {
        KvWriterPort p = port;
        if (p == null || closed) {
            return unavailableStatus();
        }
        batchLock.lock();
        try {
            return p.delete(key, keyOffset, keyLength, TopicAckMode.FIRE_AND_FORGET, sequence);
        } catch (IllegalStateException ex) {
            return closedOrRethrow(ex);
        } finally {
            batchLock.unlock();
        }
    }

    /** {@code replicate_once} delete; not allowed inside a batch. */
    public int deleteAck(MemorySegment key, long keyOffset, long keyLength, AckCallback callback, Object context) {
        KvWriterPort p = port;
        if (p == null || closed) {
            return unavailableStatus();
        }
        int status;
        long untracked = 0;
        batchLock.lock();
        try {
            rejectAckInBatch();
            status = p.delete(key, keyOffset, keyLength, TopicAckMode.REPLICATE_ONCE, sequence);
            if (status == RingloomStatus.OK && !track(p, sequence[0], callback, context)) {
                untracked = sequence[0];
            }
        } catch (IllegalStateException ex) {
            return closedOrRethrow(ex);
        } finally {
            batchLock.unlock();
        }
        completeUntracked(untracked, callback, context);
        return status;
    }

    /** Fire-and-forget clear of every entry. */
    public int clear() {
        KvWriterPort p = port;
        if (p == null || closed) {
            return unavailableStatus();
        }
        batchLock.lock();
        try {
            return p.clear(TopicAckMode.FIRE_AND_FORGET, sequence);
        } catch (IllegalStateException ex) {
            return closedOrRethrow(ex);
        } finally {
            batchLock.unlock();
        }
    }

    /** {@code replicate_once} clear; not allowed inside a batch. */
    public int clearAck(AckCallback callback, Object context) {
        KvWriterPort p = port;
        if (p == null || closed) {
            return unavailableStatus();
        }
        int status;
        long untracked = 0;
        batchLock.lock();
        try {
            rejectAckInBatch();
            status = p.clear(TopicAckMode.REPLICATE_ONCE, sequence);
            if (status == RingloomStatus.OK && !track(p, sequence[0], callback, context)) {
                untracked = sequence[0];
            }
        } catch (IllegalStateException ex) {
            return closedOrRethrow(ex);
        } finally {
            batchLock.unlock();
        }
        completeUntracked(untracked, callback, context);
        return status;
    }

    /**
     * Opens a batch owned by the calling thread. On {@code OK} the caller holds the writer lock until
     * the batch is committed or abandoned.
     *
     * @return the native status, or {@code SHUTDOWN}
     * @throws IllegalStateException if this thread already has an open batch
     */
    public int beginBatch() {
        KvWriterPort p = port;
        if (p == null || closed) {
            return unavailableStatus();
        }
        batchLock.lock();
        if (inBatch) {
            batchLock.unlock();
            throw new IllegalStateException("kv store '" + name + "': batch already open on this thread");
        }
        int status;
        try {
            status = p.beginBatch();
        } catch (IllegalStateException ex) {
            batchLock.unlock();
            return closedOrRethrow(ex);
        }
        if (status != RingloomStatus.OK) {
            batchLock.unlock();
            return status;
        }
        inBatch = true;
        return status;
    }

    /**
     * Commits the calling thread's batch (fire-and-forget). On non-{@code OK} the batch stays buffered
     * and the lock stays held.
     *
     * @throws IllegalStateException if the calling thread does not own an open batch
     */
    public int commitBatch() {
        return commit(TopicAckMode.FIRE_AND_FORGET, null, null);
    }

    /**
     * Commits the calling thread's batch with {@code replicate_once}; the callback covers the frames
     * published by this commit only.
     *
     * @throws IllegalStateException if the calling thread does not own an open batch
     */
    public int commitBatchAck(AckCallback callback, Object context) {
        return commit(TopicAckMode.REPLICATE_ONCE, callback, context);
    }

    /**
     * Discards the calling thread's buffered batch and releases the writer lock. Frames already
     * auto-flushed by the batch are not recalled. No-op when the calling thread has no open batch.
     */
    public void abandonBatch() {
        if (!batchLock.isHeldByCurrentThread() || !inBatch) {
            return;
        }
        try {
            KvWriterPort p = port;
            if (p != null && !closed) {
                p.abandonBatch();
            }
        } catch (IllegalStateException ex) {
            if (!closed) {
                throw ex;
            }
        } finally {
            inBatch = false;
            batchLock.unlock();
        }
    }

    private int commit(TopicAckMode mode, AckCallback callback, Object context) {
        if (!batchLock.isHeldByCurrentThread() || !inBatch) {
            throw new IllegalStateException(
                    "kv store '" + name + "': commitBatch called without an open batch on this thread");
        }
        KvWriterPort p = port;
        if (closed) {
            inBatch = false;
            batchLock.unlock();
            return RingloomHandlerStatus.SHUTDOWN;
        }
        int status;
        try {
            status = p.commitBatch(mode, sequence);
        } catch (IllegalStateException ex) {
            if (closed) {
                inBatch = false;
                batchLock.unlock();
                return RingloomHandlerStatus.SHUTDOWN;
            }
            throw ex;
        }
        if (status == RingloomStatus.OK) {
            long untracked = 0;
            if (mode == TopicAckMode.REPLICATE_ONCE && !track(p, sequence[0], callback, context)) {
                untracked = sequence[0];
            }
            // Close the batch before any fallback callback runs so the callback sees a consistent store.
            inBatch = false;
            batchLock.unlock();
            completeUntracked(untracked, callback, context);
        }
        return status;
    }

    private void rejectAckInBatch() {
        if (inBatch) {
            throw new IllegalStateException(
                    "kv store '" + name + "': ack callbacks inside a batch must be attached to commitBatchAck");
        }
    }

    /**
     * Registers the ack with the control-thread registry. Must be called while holding
     * {@code batchLock} so registration order matches publish order.
     *
     * @return {@code false} when the write was accepted but could not be tracked (pool full or
     *     shutting down) and {@code callback} still needs completing via {@link #completeUntracked}
     */
    private boolean track(KvWriterPort p, long seq, AckCallback callback, Object context) {
        if (seq <= 0) {
            return true;
        }
        long deadline = ackTimeoutNanos > 0 ? System.nanoTime() + ackTimeoutNanos : 0;
        return acks.register(seq, p.leaderEpoch(), callback, context, deadline) || callback == null;
    }

    /**
     * Completes a callback that {@link #track} could not register. Runs on the writing thread, and
     * must be called only after {@code batchLock} is released and any batch is closed, so the
     * callback may freely write to (or batch on) this store.
     */
    private void completeUntracked(long seq, AckCallback callback, Object context) {
        if (seq <= 0) {
            return;
        }
        try {
            callback.onComplete(seq, AckStatus.SHUTDOWN, context);
        } catch (Throwable ex) {
            if (logger != null) {
                logger.warn("kv store '{}': ack callback threw for sequence {}", name, seq, ex);
            }
        }
    }

    private int unavailableStatus() {
        return closed ? RingloomHandlerStatus.SHUTDOWN : RingloomStatus.NOT_READY;
    }

    private int closedOrRethrow(IllegalStateException ex) {
        if (closed) {
            return RingloomHandlerStatus.SHUTDOWN;
        }
        throw ex;
    }

    // ---- runtime-internal ------------------------------------------------------------------------

    String storeName() {
        return name;
    }

    KvStoreRuntimeConfig settings() {
        return settings;
    }

    KvStoreConfig requestedConfig() {
        return requestedConfig;
    }

    /** Sets the requested writer config before open (declaration phase only). */
    void requestConfig(KvStoreConfig requested) {
        this.requestedConfig = requested;
    }

    TopicAckRegistry ackRegistry() {
        return acks;
    }

    long transitions() {
        return transitions;
    }

    /**
     * Native state handler target. Runs inside native control polling (or a startup open) where KV,
     * service and client calls are forbidden: records only, no allocation, no logging.
     */
    void recordState(int nativeState, long epoch) {
        long w = ringWrite;
        if (w - ringRead >= RING_SIZE) {
            // Overflow: keep the newest state in the last slot.
            int last = (int) ((w - 1) & RING_MASK);
            ringStates[last] = nativeState;
            ringEpochs[last] = epoch;
            ringOverflowed = true;
            return;
        }
        int index = (int) (w & RING_MASK);
        ringStates[index] = nativeState;
        ringEpochs[index] = epoch;
        ringWrite = w + 1;
    }

    /**
     * Attaches the opened native writer. Transitions recorded during the open are discarded and the
     * current state is recorded as the initial transition (dispatched with {@code previous == null}).
     */
    void attach(KvWriterPort opened) {
        KvStoreConfig effective = opened.config();
        int nativeState = opened.state();
        long epoch = opened.writerEpoch();
        ringRead = ringWrite;
        ringOverflowed = false;
        recordState(nativeState, epoch);
        KvWriterState initial = effectiveState(nativeState, epoch);
        config = effective;
        writerEpoch = epoch;
        state = initial == null ? KvWriterState.QUEUED : initial;
        port = opened;
    }

    /** Control thread: drains state transitions and polls ack feedback. Returns work done. */
    int afterControlPoll() {
        if (closed) {
            return 0;
        }
        int work = drainStateTransitions();
        if (acks.pendingCount() > 0) {
            KvWriterPort p = port;
            if (p != null) {
                AckFeedbackPoller.poll(acks, p.leaderEpoch(), p.ackedSequence());
                work++;
            }
        }
        return work;
    }

    /** Control thread: publishes writer state, epoch and transition count to the metrics. Allocation-free. */
    int refreshMetrics() {
        metrics.update(state, writerEpoch, transitions);
        return 1;
    }

    /** Control thread (scheduler): times out overdue acks. */
    int sweepAckTimeouts(long nowNanos) {
        if (ackTimeoutNanos <= 0 || closed) {
            return 0;
        }
        return acks.sweepTimeouts(nowNanos);
    }

    int drainStateTransitions() {
        long r = ringRead;
        long w = ringWrite;
        if (ringOverflowed) {
            ringOverflowed = false;
            if (logger != null) {
                logger.warn("kv writer '{}': state transition ring overflowed; intermediate states were dropped", name);
            }
        }
        int work = 0;
        while (r < w) {
            int index = (int) (r & RING_MASK);
            int nativeState = ringStates[index];
            long epoch = ringEpochs[index];
            r++;
            ringRead = r;
            applyTransition(nativeState, epoch);
            work++;
        }
        return work;
    }

    /**
     * Maps a native state to the framework state. A native {@code GRANTED} with lease epoch {@code 0}
     * is reported as {@code QUEUED}: lease epochs start at 1, and the native writer has been observed
     * to report {@code (GRANTED, 0)} for a standby whose writes are still rejected with
     * {@code KV_NOT_WRITER} (docs/impl/16-kv-stores.md §15).
     */
    static KvWriterState effectiveState(int nativeState, long epoch) {
        KvWriterState state = KvWriterState.fromNative(nativeState);
        return state == KvWriterState.GRANTED && epoch <= 0 ? KvWriterState.QUEUED : state;
    }

    private void applyTransition(int nativeState, long epoch) {
        KvWriterState next = effectiveState(nativeState, epoch);
        if (next == null) {
            if (logger != null) {
                logger.warn("kv writer '{}': unknown native writer state {}", name, nativeState);
            }
            return;
        }
        if (next == dispatchedState && epoch == dispatchedEpoch) {
            return;
        }
        KvWriterState previous = dispatchedState;
        dispatchedState = next;
        dispatchedEpoch = epoch;
        state = next;
        writerEpoch = epoch;
        transitions++;
        if (next == KvWriterState.REVOKED) {
            acks.completePending(AckStatus.REVOKED);
        }
        if (logger != null) {
            logger.info("kv writer '{}' {} -> {} epoch={}", name, previous == null ? "OPENED" : previous, next, epoch);
        }
        if (stateDispatcher != null) {
            try {
                stateDispatcher.accept(new KvWriterStateEvent(name, next, previous, epoch));
            } catch (Throwable ex) {
                if (logger != null) {
                    logger.error("kv writer '{}': writer state handler failed", name, ex);
                }
            }
        }
    }

    /**
     * Closes the writer: completes pending acks with {@code SHUTDOWN}, then closes the native writer
     * (which releases the lease so a standby can take over).
     */
    void close(long closeTimeoutMillis) {
        if (closed) {
            return;
        }
        closed = true;
        state = KvWriterState.CLOSED;
        acks.completeAll(AckStatus.SHUTDOWN);
        KvWriterPort p = port;
        if (p == null) {
            return;
        }
        boolean locked = false;
        try {
            locked = batchLock.tryLock(closeTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (!locked) {
            if (logger != null) {
                logger.warn(
                        "kv writer '{}': writer lock still held by another thread at close (open batch?);"
                                + " closing anyway, buffered batch records are discarded",
                        name);
            }
        } else if (inBatch) {
            if (logger != null) {
                logger.warn("kv writer '{}': closing with an uncommitted batch; buffered records are discarded", name);
            }
            inBatch = false;
        }
        try {
            p.close();
        } catch (RuntimeException ex) {
            if (logger != null) {
                logger.warn("kv writer '{}': failed to close native writer", name, ex);
            }
        } finally {
            if (locked) {
                batchLock.unlock();
            }
        }
    }
}
