// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

public interface MessageDecoder<T> {
    int templateId();

    T decode(ReadableMessageBuffer source, DecodeContext context);
}
