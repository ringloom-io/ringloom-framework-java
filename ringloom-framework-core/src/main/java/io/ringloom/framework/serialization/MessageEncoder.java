// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

public interface MessageEncoder<T> {
    int templateId();

    int encodedLength(T value, EncodeContext context);

    int encode(T value, WritableMessageBuffer target, EncodeContext context);
}
