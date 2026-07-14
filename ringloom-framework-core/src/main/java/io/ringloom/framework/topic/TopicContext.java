// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.topic;

import io.ringloom.framework.RingloomRuntime;
import java.lang.foreign.MemorySegment;

/**
 * Per-thread mutable dispatch context for topic messages, analogous to
 * {@link io.ringloom.framework.dispatch.MessageContext}.
 *
 * <p>The {@code consumerThread} path reuses a single {@code TopicContext} per poll thread: the source
 * calls {@link #updateFrom(TopicMessage, String)} before each dispatch. The partitioned-worker and
 * virtual-thread paths build a fresh context over a copied payload via {@link #updateCopied} and a
 * single-field {@link #payloadSegment(MemorySegment)} setter.
 *
 * <p>The payload segment is borrowed: see {@link TopicMessage} for its validity window.
 */
public final class TopicContext {
    private RingloomRuntime runtime;
    private long topicId;
    private String topicName;
    private long index;
    private MemorySegment payloadSegment = MemorySegment.NULL;
    private Object tracingContext;

    /** Creates a new empty topic context. */
    public TopicContext() {}

    /** Creates a new topic context bound to a runtime. */
    public TopicContext(RingloomRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Updates this context from a borrowed topic message.
     *
     * @param message   the borrowed topic message
     * @param topicName the topic name (resolved by the source from the topic id)
     */
    public void updateFrom(TopicMessage message, String topicName) {
        this.topicId = message.topicId();
        this.topicName = topicName;
        this.index = message.index();
        this.payloadSegment = message.payloadSegment();
        this.tracingContext = null;
    }

    /**
     * Updates this context from copied primitive fields, for worker/virtual-thread dispatch.
     *
     * @param topicId        the broker-assigned topic id
     * @param topicName      the topic name
     * @param index          the ringloom-queue index of the message
     * @param payloadSegment the worker/virtual-thread-owned copied payload segment
     */
    public void updateCopied(long topicId, String topicName, long index, MemorySegment payloadSegment) {
        this.topicId = topicId;
        this.topicName = topicName;
        this.index = index;
        this.payloadSegment = payloadSegment == null ? MemorySegment.NULL : payloadSegment;
        this.tracingContext = null;
    }

    /** Binds this context to a runtime. */
    public void runtime(RingloomRuntime runtime) {
        this.runtime = runtime;
    }

    /** Returns the runtime bound to this context, or {@code null} before binding. */
    public RingloomRuntime runtime() {
        return runtime;
    }

    /** Sets the tracing context carrier for this dispatch. */
    public void tracingContext(Object tracingContext) {
        this.tracingContext = tracingContext;
    }

    /** Returns the tracing context carrier, or {@code null}. */
    public Object tracingContext() {
        return tracingContext;
    }

    /** Sets the borrowed/copied payload segment for this dispatch. */
    public void payloadSegment(MemorySegment payloadSegment) {
        this.payloadSegment = payloadSegment == null ? MemorySegment.NULL : payloadSegment;
    }

    /** Returns the broker-assigned topic id. */
    public long topicId() {
        return topicId;
    }

    /** Returns the topic name. */
    public String topicName() {
        return topicName;
    }

    /** Returns the ringloom-queue index of the message. */
    public long index() {
        return index;
    }

    /** Returns the borrowed/copied payload segment. */
    public MemorySegment payloadSegment() {
        return payloadSegment;
    }
}
