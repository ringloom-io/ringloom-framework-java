// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

/**
 * Decodes an inbound RingLoom payload into an application value.
 *
 * @param <T> the decoded value type
 */
public interface MessageDecoder<T> {
    int templateId();

    T decode(ReadableMessageBuffer source, DecodeContext context);
}
