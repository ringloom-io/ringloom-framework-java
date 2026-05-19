// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.request;

/**
 * Tracks pending request/response interactions for generated client code.
 */
public interface RequestResponseRegistry {
    PendingRequest acquire();

    int register(PendingRequest request);

    PendingRequest resolve(long correlationId, int responseTemplateId);

    void complete(PendingRequest request, int status);

    int complete(PendingRequest request, long correlationId, int responseTemplateId, int status, Object responseValue);

    int cancel(PendingRequest request, int status);

    void release(PendingRequest request);

    void completeAll(int status);
}
