// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

import java.lang.foreign.MemorySegment;

/**
 * Wraps an inbound payload with a flyweight view instead of allocating a decoded object.
 *
 * @param <T> the flyweight type
 */
public interface FlyweightDecoder<T> {
    int templateId();

    T wrap(MemorySegment payload, DecodeContext context);
}
