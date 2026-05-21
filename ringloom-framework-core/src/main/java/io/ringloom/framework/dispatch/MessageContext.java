// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.service.RingloomMessage;
import java.lang.foreign.MemorySegment;

/**
 * Mutable message metadata passed to handlers and dispatch policies.
 */
public final class MessageContext {
    private RingloomRuntime runtime;
    private long correlationId;
    private short sourceNodeId;
    private short sourceServiceId;
    private short targetNodeId;
    private short targetServiceId;
    private int templateId;
    private int flags;
    private MemorySegment payloadSegment = MemorySegment.NULL;
    private Object tracingContext;

    public MessageContext() {}

    public MessageContext(RingloomRuntime runtime) {
        this.runtime = runtime;
    }

    public void runtime(RingloomRuntime runtime) {
        this.runtime = runtime;
    }

    public void tracingContext(Object tracingContext) {
        this.tracingContext = tracingContext;
    }

    public void payloadSegment(MemorySegment payloadSegment) {
        this.payloadSegment = payloadSegment;
    }

    public void updateFrom(RingloomMessage message) {
        correlationId = message.correlationId();
        sourceNodeId = message.sourceNodeId();
        sourceServiceId = message.sourceServiceId();
        targetNodeId = message.targetNodeId();
        targetServiceId = message.targetServiceId();
        templateId = message.templateId();
        flags = message.flags();
        payloadSegment = message.payloadSegment();
        tracingContext = null;
    }

    public void updateCopied(
            long correlationId,
            short sourceNodeId,
            short sourceServiceId,
            short targetNodeId,
            short targetServiceId,
            int templateId,
            int flags,
            MemorySegment payloadSegment) {
        this.correlationId = correlationId;
        this.sourceNodeId = sourceNodeId;
        this.sourceServiceId = sourceServiceId;
        this.targetNodeId = targetNodeId;
        this.targetServiceId = targetServiceId;
        this.templateId = templateId;
        this.flags = flags;
        this.payloadSegment = payloadSegment;
        tracingContext = null;
    }

    public RingloomRuntime runtime() {
        return runtime;
    }

    public Object tracingContext() {
        return tracingContext;
    }

    public long correlationId() {
        return correlationId;
    }

    public short sourceNodeId() {
        return sourceNodeId;
    }

    public short sourceServiceId() {
        return sourceServiceId;
    }

    public short targetNodeId() {
        return targetNodeId;
    }

    public short targetServiceId() {
        return targetServiceId;
    }

    public int templateId() {
        return templateId;
    }

    public int flags() {
        return flags;
    }

    public MemorySegment payloadSegment() {
        return payloadSegment;
    }
}
