// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.request;

import io.ringloom.framework.dispatch.MessageContext;

/**
 * Decodes a correlated response payload for a pending generated request.
 *
 * @param <T> the decoded response type
 */
@FunctionalInterface
public interface ResponseDecoder<T> {
    T decode(MessageContext context);
}
