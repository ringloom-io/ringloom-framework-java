// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

/**
 * Encodes an application value into an outbound RingLoom message payload.
 *
 * @param <T> the value type encoded by this encoder
 */
public interface MessageEncoder<T> {
    int templateId();

    int encodedLength(T value, EncodeContext context);

    int encode(T value, WritableMessageBuffer target, EncodeContext context);
}
