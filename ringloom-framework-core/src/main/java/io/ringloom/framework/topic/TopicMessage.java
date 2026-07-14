// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.topic;

import java.lang.foreign.MemorySegment;

/**
 * Reusable, borrowed-payload view of a single polled topic message.
 *
 * <p>{@code TopicMessage} does <strong>not</strong> implement {@link io.ringloom.service.RingloomMessage};
 * it is a distinct value type for the topic dispatch path. The payload segment is borrowed from native
 * memory and is valid only during the handler invocation on the {@code consumerThread} path, or until
 * the owning {@code TopicMessageSource.offer} call returns on the partitioned/virtual-thread paths
 * (which copy the bytes before returning). It is reused across polls to keep the steady-state hot path
 * zero-allocation.
 */
public final class TopicMessage {
    private long topicId;
    private MemorySegment payloadSegment = MemorySegment.NULL;
    private long index;

    /** Creates a new empty topic message view. */
    public TopicMessage() {}

    /**
     * Refreshes this view with the borrowed payload from a poll.
     *
     * @param topicId        the broker-assigned topic id
     * @param payloadSegment the borrowed payload segment, valid only until the next poll
     * @param index          the ringloom-queue index of the message
     */
    public void updateFrom(long topicId, MemorySegment payloadSegment, long index) {
        this.topicId = topicId;
        this.payloadSegment = payloadSegment == null ? MemorySegment.NULL : payloadSegment;
        this.index = index;
    }

    /** Returns the broker-assigned topic id this message was polled from. */
    public long topicId() {
        return topicId;
    }

    /**
     * Returns the borrowed payload segment.
     *
     * <p>The segment is valid only for the duration described in the class javadoc.
     *
     * @return the borrowed payload segment, or {@link MemorySegment#NULL} for an empty payload
     */
    public MemorySegment payloadSegment() {
        return payloadSegment;
    }

    /** Returns the ringloom-queue index of the message. */
    public long index() {
        return index;
    }
}
