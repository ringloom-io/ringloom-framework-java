// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.topic.ack;

import io.ringloom.framework.metrics.RingloomCounter;
import io.ringloom.framework.metrics.RingloomGauge;
import io.ringloom.framework.metrics.RingloomMetrics;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;

/**
 * Per-publisher registry of pending {@code replicate_once} topic acknowledgements.
 *
 * <p>One instance is owned by each generated publisher proxy. Pending entries are keyed by primitive
 * {@code long} publish index; the registry completes entries as the framework's control thread polls
 * {@code TopicPublisher.replicatedCount()} (the replicated publish count / high-water mark) and
 * advances it via {@link #advanceHwm(long, long)}.
 *
 * <p>The implementation is allocation-free in steady state: {@link PendingAck} entries are pooled and
 * reused, mirroring {@link io.ringloom.framework.request.PooledRequestResponseRegistry}. Completion
 * callbacks run inline on the thread that drives {@code advanceHwm}/{@code sweepTimeouts} (the
 * framework control thread).
 *
 * <p>All mutating methods are {@code synchronized}: the publish thread registers entries while the
 * control thread advances the HWM and sweeps timeouts. The critical sections are short (prefix
 * completion + pooled release) and callbacks run inside the lock to preserve the "inline on control
 * thread" contract; callbacks must not block.
 */
public final class TopicAckRegistry {
    private final int maxPending;
    private final RingloomMetrics metrics;
    private final Logger logger;

    private final PendingAck[] slots;
    private final Deque<PendingAck> free;
    private final List<PendingAck> pending = new ArrayList<>();

    private long seenEpoch = 0L;
    private long currentHwm = 0L;
    private boolean shutdown = false;

    // Metrics handles (nullable: metrics may be unavailable).
    private final RingloomGauge hwmGauge;
    private final RingloomGauge pendingGauge;
    private final RingloomCounter completedCounter;
    private final RingloomCounter timeoutCounter;
    private final RingloomCounter leaderChangedCounter;
    private final RingloomCounter shutdownCounter;

    /**
     * Creates a new registry.
     *
     * @param maxPending the pool capacity; must be {@code > 0}
     * @param metrics    the runtime metrics registry (used for ack gauges/counters)
     * @param logger     the runtime logger
     */
    public TopicAckRegistry(int maxPending, RingloomMetrics metrics, Logger logger) {
        if (maxPending <= 0) {
            throw new IllegalArgumentException("maxPending must be positive");
        }
        this.maxPending = maxPending;
        this.metrics = metrics;
        this.logger = logger;
        this.slots = new PendingAck[maxPending];
        this.free = new ArrayDeque<>(maxPending);
        for (int i = 0; i < maxPending; i++) {
            PendingAck entry = new PendingAck(i);
            slots[i] = entry;
            free.addLast(entry);
        }
        this.hwmGauge = registerGauge(metrics, "topic.ack.hwm");
        this.pendingGauge = registerGauge(metrics, "topic.ack.pending");
        this.completedCounter = registerCounter(metrics, "topic.ack.completed");
        this.timeoutCounter = registerCounter(metrics, "topic.ack.timeout");
        this.leaderChangedCounter = registerCounter(metrics, "topic.ack.leader_changed");
        this.shutdownCounter = registerCounter(metrics, "topic.ack.shutdown");
    }

    private static RingloomGauge registerGauge(RingloomMetrics metrics, String name) {
        try {
            return metrics.registerGauge(name);
        } catch (UnsupportedOperationException ex) {
            return null;
        }
    }

    private static RingloomCounter registerCounter(RingloomMetrics metrics, String name) {
        try {
            return metrics.registerCounter(name);
        } catch (UnsupportedOperationException ex) {
            return null;
        }
    }

    /**
     * Registers a pending acknowledgement.
     *
     * <p>Allocation-free in steady state: pooled entries are reused. Returns {@code false} (without
     * invoking the callback) when the pool is exhausted or the registry has been shut down.
     *
     * @param publishIndex the publish index returned by the publish call
     * @param leaderEpoch  the publisher's leader epoch at publish time
     * @param callback     the caller-reused completion callback
     * @param userContext  opaque caller context passed back in the callback
     * @param timeoutNanos absolute deadline ({@link System#nanoTime()} base); {@code <= 0} disables timeout
     * @return {@code true} if registered, {@code false} if the pool is full or the registry is shut down
     */
    public synchronized boolean register(
            long publishIndex, long leaderEpoch, AckCallback callback, Object userContext, long timeoutNanos) {
        if (shutdown) {
            return false;
        }
        PendingAck entry = free.pollFirst();
        if (entry == null) {
            return false;
        }
        entry.set(publishIndex, leaderEpoch, callback, userContext, timeoutNanos);
        pending.add(entry);
        return true;
    }

    /**
     * Advances the replicated high-water mark for the given epoch.
     *
     * <p>Frames whose epoch is older than {@link #knownEpoch()} are ignored (stale feedback from a
     * previous leader). For a current/new epoch, every pending entry sent under the frame's epoch
     * whose {@code publishIndex <= hwm} is completed as {@link AckStatus#ACKED}; entries sent under a
     * different epoch are left for {@link #onLeaderChanged(long)}.
     *
     * @param leaderEpoch the publisher's current leader epoch
     * @param hwm         the replicated publish count reported by the publisher
     */
    public synchronized void advanceHwm(long leaderEpoch, long hwm) {
        if (leaderEpoch < seenEpoch) {
            return;
        }
        if (leaderEpoch > seenEpoch) {
            seenEpoch = leaderEpoch;
        }
        if (hwm <= currentHwm) {
            return;
        }
        currentHwm = hwm;
        if (hwmGauge != null) {
            hwmGauge.set(hwm);
        }
        completeAckedPrefix(leaderEpoch, hwm);
    }

    /**
     * Reacts to a leader epoch change observed by the control thread.
     *
     * <p>Completes every pending entry whose epoch is older than {@code newLeaderEpoch} as
     * {@link AckStatus#LEADER_CHANGED}.
     *
     * @param newLeaderEpoch the publisher's new leader epoch
     */
    public synchronized void onLeaderChanged(long newLeaderEpoch) {
        completeStaleEpochs(newLeaderEpoch);
    }

    /**
     * Completes pending entries whose deadline has passed.
     *
     * @param nowNanos the current {@link System#nanoTime()} value
     * @return the number of entries timed out
     */
    public synchronized int sweepTimeouts(long nowNanos) {
        if (pending.isEmpty()) {
            return 0;
        }
        int swept = 0;
        for (int i = pending.size() - 1; i >= 0; i--) {
            PendingAck entry = pending.get(i);
            long deadline = entry.deadlineNanos;
            if (deadline > 0 && nowNanos >= deadline) {
                pending.remove(i);
                complete(entry, AckStatus.ACK_TIMEOUT);
                swept++;
            }
        }
        return swept;
    }

    /**
     * Completes every pending entry with the given status and blocks further registrations.
     *
     * @param status the completion status (typically {@link AckStatus#SHUTDOWN})
     */
    public synchronized void completeAll(AckStatus status) {
        shutdown = true;
        for (int i = pending.size() - 1; i >= 0; i--) {
            PendingAck entry = pending.remove(i);
            complete(entry, status);
        }
    }

    /** Returns the largest leader epoch observed so far. */
    public synchronized long knownEpoch() {
        return seenEpoch;
    }

    /** Returns the current replicated high-water mark. */
    public synchronized long currentHwm() {
        return currentHwm;
    }

    /** Returns the number of currently pending entries. */
    public synchronized int pendingCount() {
        return pending.size();
    }

    /** Returns the configured pool capacity. */
    public int maxPending() {
        return maxPending;
    }

    private void completeAckedPrefix(long leaderEpoch, long hwm) {
        for (int i = pending.size() - 1; i >= 0; i--) {
            PendingAck entry = pending.get(i);
            if (entry.leaderEpoch == leaderEpoch && entry.publishIndex <= hwm) {
                pending.remove(i);
                complete(entry, AckStatus.ACKED);
            }
        }
    }

    private void completeStaleEpochs(long newLeaderEpoch) {
        if (newLeaderEpoch <= seenEpoch) {
            return;
        }
        seenEpoch = newLeaderEpoch;
        for (int i = pending.size() - 1; i >= 0; i--) {
            PendingAck entry = pending.get(i);
            if (entry.leaderEpoch < newLeaderEpoch) {
                pending.remove(i);
                complete(entry, AckStatus.LEADER_CHANGED);
            }
        }
    }

    private void complete(PendingAck entry, AckStatus status) {
        long publishIndex = entry.publishIndex;
        AckCallback callback = entry.callback;
        Object userContext = entry.userContext;
        entry.clear();
        free.addLast(entry);
        incrementCounter(counterFor(status));
        if (pendingGauge != null) {
            pendingGauge.set(pending.size());
        }
        if (callback != null) {
            try {
                callback.onComplete(publishIndex, status, userContext);
            } catch (Throwable ex) {
                if (logger != null) {
                    logger.warn("Topic ack callback threw for index {}", publishIndex, ex);
                }
            }
        }
    }

    private RingloomCounter counterFor(AckStatus status) {
        return switch (status) {
            case ACKED -> completedCounter;
            case ACK_TIMEOUT -> timeoutCounter;
            case LEADER_CHANGED -> leaderChangedCounter;
            case SHUTDOWN -> shutdownCounter;
        };
    }

    private void incrementCounter(RingloomCounter counter) {
        if (counter != null) {
            counter.increment();
        }
    }
}
