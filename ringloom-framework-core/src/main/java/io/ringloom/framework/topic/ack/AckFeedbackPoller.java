// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.topic.ack;

/**
 * Shared control-thread ack feedback step used by topic publishers and KV writers.
 *
 * <p>Both expose a monotonic per-publisher acknowledged sequence and the leader epoch they publish
 * under. When the epoch increases, entries registered under older epochs complete with
 * {@link AckStatus#LEADER_CHANGED}; then entries under the current epoch whose sequence is at or below
 * the acknowledged sequence complete with {@link AckStatus#ACKED}. Allocation-free.
 */
public final class AckFeedbackPoller {
    private AckFeedbackPoller() {}

    /**
     * Applies one round of ack feedback to a registry.
     *
     * @param registry       the registry to drive
     * @param leaderEpoch    the publisher's current leader epoch ({@code 0} = none)
     * @param ackedSequence  the publisher's acknowledged (replicated) sequence
     */
    public static void poll(TopicAckRegistry registry, long leaderEpoch, long ackedSequence) {
        if (leaderEpoch > 0 && leaderEpoch > registry.knownEpoch()) {
            // Complete stale-epoch entries before advancing: advanceReplicatedCount records the new
            // epoch, after which onLeaderChanged would see no change.
            registry.onLeaderChanged(leaderEpoch);
        }
        registry.advanceReplicatedCount(leaderEpoch, ackedSequence);
    }
}
