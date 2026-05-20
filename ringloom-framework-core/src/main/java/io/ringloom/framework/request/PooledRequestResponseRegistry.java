// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.request;

import io.ringloom.framework.status.RingloomHandlerStatus;
import java.util.ArrayDeque;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * {@link RequestResponseRegistry} backed by a fixed pool of reusable {@link PendingRequest}
 * instances.
 */
public final class PooledRequestResponseRegistry implements RequestResponseRegistry {
    private final PendingRequest[] slots;
    private final ArrayDeque<PendingRequest> free;
    private final Long2ObjectHashMap<PendingRequest> byCorrelationId;

    public PooledRequestResponseRegistry(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.slots = new PendingRequest[capacity];
        this.free = new ArrayDeque<>(capacity);
        this.byCorrelationId = new Long2ObjectHashMap<>(capacity, 0.6f);
        for (int i = 0; i < capacity; i++) {
            PendingRequest request = new PendingRequest(i);
            slots[i] = request;
            free.addLast(request);
        }
    }

    @Override
    public synchronized PendingRequest acquire() {
        PendingRequest request = free.pollFirst();
        if (request == null) {
            return null;
        }
        long correlationId = correlationIdFor(request);
        return request.prepare(correlationId, -1, null, null, 0, null);
    }

    @Override
    public synchronized int register(PendingRequest request) {
        if (request == null || request.registered()) {
            return RingloomHandlerStatus.REQUEST_CANCELLED;
        }
        if (byCorrelationId.containsKey(request.correlationId())) {
            return RingloomHandlerStatus.REQUEST_CANCELLED;
        }
        byCorrelationId.put(request.correlationId(), request);
        request.markRegistered();
        return RingloomHandlerStatus.OK;
    }

    @Override
    public synchronized PendingRequest resolve(long correlationId, int responseTemplateId) {
        PendingRequest request = byCorrelationId.get(correlationId);
        if (request == null || request.expectedResponseTemplateId() != responseTemplateId) {
            return null;
        }
        return request;
    }

    @Override
    public synchronized void complete(PendingRequest request, int status) {
        complete(
                request,
                request == null ? 0 : request.correlationId(),
                request == null ? -1 : request.expectedResponseTemplateId(),
                status,
                null);
    }

    @Override
    public synchronized int complete(
            PendingRequest request, long correlationId, int responseTemplateId, int status, Object responseValue) {
        if (request == null) {
            return RingloomHandlerStatus.REQUEST_CANCELLED;
        }
        PendingRequest registered = byCorrelationId.get(correlationId);
        if (registered != request || request.expectedResponseTemplateId() != responseTemplateId) {
            int completionStatus = request.completionStatus();
            return completionStatus == 0 ? RingloomHandlerStatus.REQUEST_CANCELLED : completionStatus;
        }
        byCorrelationId.remove(correlationId);
        request.complete(status, responseValue);
        if (request.awaiter() == null) {
            release(request);
        }
        return status;
    }

    @Override
    public synchronized int cancel(PendingRequest request, int status) {
        if (request == null) {
            return RingloomHandlerStatus.REQUEST_CANCELLED;
        }
        if (!request.registered()) {
            return request.completionStatus();
        }
        byCorrelationId.remove(request.correlationId());
        request.complete(status, null);
        if (request.awaiter() == null) {
            release(request);
        }
        return status;
    }

    @Override
    public synchronized void release(PendingRequest request) {
        if (request == null
                || (!request.registered()
                        && request.correlationId() == 0
                        && request.expectedResponseTemplateId() == -1)) {
            return;
        }
        byCorrelationId.remove(request.correlationId());
        releaseSlot(request);
    }

    @Override
    public synchronized void completeAll(int status) {
        for (PendingRequest request : byCorrelationId.values()) {
            request.complete(status, null);
            if (request.awaiter() == null) {
                releaseSlot(request);
            }
        }
        byCorrelationId.clear();
    }

    private void releaseSlot(PendingRequest request) {
        request.clear();
        free.addLast(request);
    }

    private static long correlationIdFor(PendingRequest request) {
        return ((request.generation() & 0xffff_ffffL) << 32) | (request.slot() & 0xffff_ffffL);
    }
}
