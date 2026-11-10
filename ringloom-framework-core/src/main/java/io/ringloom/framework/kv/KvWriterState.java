// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

/**
 * Framework view of a KV writer's lease state. The first four constants have the same ordinal as the
 * native writer state values ({@code io.ringloom.service.KvWriter.GRANTED} etc.).
 */
public enum KvWriterState {
    /** This writer holds the lease; writes are accepted. */
    GRANTED,
    /** Queued as a standby; writes return {@code KV_NOT_WRITER}. */
    QUEUED,
    /** The lease was revoked; writes return {@code KV_NOT_WRITER}. */
    REVOKED,
    /** The lease is being reasserted after a leader change; writes return {@code TOPIC_LEADER_UNAVAILABLE}. */
    REASSERTING,
    /** The writer handle was closed by the framework. */
    CLOSED;

    private static final KvWriterState[] VALUES = values();

    /**
     * Maps a native writer state value to the framework enum.
     *
     * @param nativeState the native state value (0..3)
     * @return the framework state, or {@code null} for unknown values
     */
    public static KvWriterState fromNative(int nativeState) {
        return nativeState >= 0 && nativeState < CLOSED.ordinal() ? VALUES[nativeState] : null;
    }
}
