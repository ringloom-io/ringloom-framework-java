// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.request;

import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.framework.status.RingloomHandlerStatus;

/**
 * Callback invoked when a generated request receives a response or terminal outcome.
 *
 * @param <T> the response payload type
 */
public interface ResponseCallback<T> {
    int onResponse(T response, MessageContext context, Object userContext);

    default int onTimeout(Object userContext) {
        return RingloomHandlerStatus.OK;
    }

    default int onFailure(int status, Object userContext) {
        return status;
    }
}
