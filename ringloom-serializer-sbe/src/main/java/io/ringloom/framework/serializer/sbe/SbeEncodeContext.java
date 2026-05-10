// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reusable SBE encoder state intended to be owned by one event-loop thread.
 */
public final class SbeEncodeContext {
    private final MemorySegmentMutableDirectBuffer buffer = new MemorySegmentMutableDirectBuffer();
    private final Map<Class<?>, Object> codecs = new HashMap<>();

    /**
     * Wraps a native target segment for SBE encoding.
     *
     * @param segment the target segment
     * @return the reusable mutable Agrona buffer
     */
    public MemorySegmentMutableDirectBuffer wrap(MemorySegment segment) {
        return buffer.wrap(segment);
    }

    /**
     * Returns the reusable mutable Agrona buffer.
     *
     * @return the mutable direct buffer
     */
    public MemorySegmentMutableDirectBuffer buffer() {
        return buffer;
    }

    /**
     * Returns a reusable codec instance scoped to this context.
     *
     * @param type the codec type
     * @param factory factory used on first access
     * @param <T> the codec type
     * @return a context-scoped codec instance
     */
    public <T> T codec(Class<T> type, Supplier<? extends T> factory) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(factory, "factory");
        Object codec = codecs.computeIfAbsent(type, ignored -> factory.get());
        return type.cast(codec);
    }
}
