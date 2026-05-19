// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.request;

/**
 * Mutable state for a single in-flight request tracked by the runtime.
 */
public final class PendingRequest {
    private final int slot;
    private long generation;
    private long correlationId;
    private int expectedResponseTemplateId;
    private ResponseCallback<?> callback;
    private Object userContext;
    private long deadlineNanos;
    private RequestAwaiter awaiter;
    private int completionStatus;
    private Object responseValue;
    private ResponseDecoder<?> responseDecoder;
    private boolean registered;

    PendingRequest(int slot) {
        this.slot = slot;
    }

    public int slot() {
        return slot;
    }

    public long generation() {
        return generation;
    }

    public long correlationId() {
        return correlationId;
    }

    public int expectedResponseTemplateId() {
        return expectedResponseTemplateId;
    }

    public ResponseCallback<?> callback() {
        return callback;
    }

    public Object userContext() {
        return userContext;
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public RequestAwaiter awaiter() {
        return awaiter;
    }

    public int completionStatus() {
        return completionStatus;
    }

    public Object responseValue() {
        return responseValue;
    }

    public boolean registered() {
        return registered;
    }

    public PendingRequest prepare(
            long correlationId,
            int expectedResponseTemplateId,
            ResponseCallback<?> callback,
            Object userContext,
            long deadlineNanos,
            RequestAwaiter awaiter) {
        return prepare(correlationId, expectedResponseTemplateId, callback, userContext, deadlineNanos, awaiter, null);
    }

    public PendingRequest prepare(
            long correlationId,
            int expectedResponseTemplateId,
            ResponseCallback<?> callback,
            Object userContext,
            long deadlineNanos,
            RequestAwaiter awaiter,
            ResponseDecoder<?> responseDecoder) {
        this.correlationId = correlationId;
        this.expectedResponseTemplateId = expectedResponseTemplateId;
        this.callback = callback;
        this.userContext = userContext;
        this.deadlineNanos = deadlineNanos;
        this.awaiter = awaiter;
        this.completionStatus = 0;
        this.responseValue = null;
        this.responseDecoder = responseDecoder;
        this.registered = false;
        return this;
    }

    public Object decodeResponse(io.ringloom.framework.dispatch.MessageContext context) {
        if (responseDecoder == null) {
            return context.payloadSegment();
        }
        return responseDecoder.decode(context);
    }

    void markRegistered() {
        registered = true;
    }

    void complete(int status, Object responseValue) {
        completionStatus = status;
        this.responseValue = responseValue;
        registered = false;
        RequestAwaiter requestAwaiter = awaiter;
        if (requestAwaiter != null) {
            requestAwaiter.complete();
        }
    }

    void clear() {
        generation++;
        correlationId = 0;
        expectedResponseTemplateId = -1;
        callback = null;
        userContext = null;
        deadlineNanos = 0;
        awaiter = null;
        completionStatus = 0;
        responseValue = null;
        responseDecoder = null;
        registered = false;
    }
}
