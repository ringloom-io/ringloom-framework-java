// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.request;

public interface RequestResponseRegistry {
    PendingRequest acquire();

    int register(PendingRequest request);

    PendingRequest resolve(long correlationId, int responseTemplateId);

    void complete(PendingRequest request, int status);

    void cancel(PendingRequest request, int status);

    void completeAll(int status);
}
