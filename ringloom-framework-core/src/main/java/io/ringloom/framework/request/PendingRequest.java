// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.request;

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

    public boolean registered() {
        return registered;
    }

    public PendingRequest prepare(
        long correlationId,
        int expectedResponseTemplateId,
        ResponseCallback<?> callback,
        Object userContext,
        long deadlineNanos,
        RequestAwaiter awaiter
    ) {
        this.correlationId = correlationId;
        this.expectedResponseTemplateId = expectedResponseTemplateId;
        this.callback = callback;
        this.userContext = userContext;
        this.deadlineNanos = deadlineNanos;
        this.awaiter = awaiter;
        this.completionStatus = 0;
        this.registered = false;
        return this;
    }

    void markRegistered() {
        registered = true;
    }

    void complete(int status) {
        completionStatus = status;
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
        registered = false;
    }
}
