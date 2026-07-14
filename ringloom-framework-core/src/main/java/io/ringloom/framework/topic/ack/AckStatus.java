// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.topic.ack;

/**
 * Outcome of a pending {@code replicate_once} topic publish, passed to {@link AckCallback}.
 */
public enum AckStatus {
    /** The publish index was applied by at least one replica (or appended on a single-node broker). */
    ACKED,
    /** The publish did not receive acknowledgement before its configured timeout. */
    ACK_TIMEOUT,
    /** The topic leader changed before the publish was acked under the previous epoch. */
    LEADER_CHANGED,
    /** The runtime is shutting down and all pending entries were released. */
    SHUTDOWN
}
