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

    /**
     * Wraps {@code payload} with a reusable flyweight.
     *
     * <p>The segment may be <strong>larger than the payload</strong>: for example KV reads pass the
     * calling thread's whole value scratch segment rather than slicing it. When
     * {@link DecodeContext#payloadLength()} is set it is authoritative and implementations must not
     * read past it (use {@link DecodeContext#payloadLength(DecodeContext, MemorySegment)}); a
     * self-describing format such as SBE may rely on its own encoded length within that bound.
     * When it is unset the whole segment is the payload.
     *
     * @param payload the borrowed payload segment, possibly larger than the payload
     * @param context the caller's decode context
     * @return the wrapped flyweight
     */
    T wrap(MemorySegment payload, DecodeContext context);
}
