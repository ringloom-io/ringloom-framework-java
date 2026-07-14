// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.topic.ack;

/**
 * Pooled, mutable pending-ack entry. One entry per outstanding {@code replicate_once} publish; reused
 * after completion.
 */
final class PendingAck {
    final int slot;

    long publishIndex;
    long leaderEpoch;
    long deadlineNanos;
    AckCallback callback;
    Object userContext;

    PendingAck(int slot) {
        this.slot = slot;
    }

    void set(long publishIndex, long leaderEpoch, AckCallback callback, Object userContext, long deadlineNanos) {
        this.publishIndex = publishIndex;
        this.leaderEpoch = leaderEpoch;
        this.callback = callback;
        this.userContext = userContext;
        this.deadlineNanos = deadlineNanos;
    }

    void clear() {
        publishIndex = 0L;
        leaderEpoch = 0L;
        deadlineNanos = 0L;
        callback = null;
        userContext = null;
    }
}
