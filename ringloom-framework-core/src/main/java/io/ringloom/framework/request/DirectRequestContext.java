// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.request;

import io.ringloom.framework.DirectSendContext;

/**
 * Reusable state holder for direct request/response APIs.
 */
public final class DirectRequestContext {
    private final DirectSendContext sendContext = new DirectSendContext();
    private PendingRequest pendingRequest;

    public DirectSendContext sendContext() {
        return sendContext;
    }

    public PendingRequest pendingRequest() {
        return pendingRequest;
    }

    public void pendingRequest(PendingRequest pendingRequest) {
        this.pendingRequest = pendingRequest;
    }
}
