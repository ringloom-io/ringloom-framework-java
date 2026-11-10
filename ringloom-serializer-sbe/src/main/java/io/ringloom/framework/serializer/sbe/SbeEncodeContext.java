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
     * Wraps the first {@code length} bytes of a native segment without slicing.
     *
     * @param segment the segment, possibly larger than {@code length}
     * @param length the payload length
     * @return the reusable Agrona buffer
     */
    public MemorySegmentMutableDirectBuffer wrap(MemorySegment segment, long length) {
        return buffer.wrap(segment, length);
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
        // Plain get/put: a computeIfAbsent capturing lambda would allocate on every call.
        Object codec = codecs.get(type);
        if (codec == null) {
            codec = Objects.requireNonNull(factory.get(), "factory returned null");
            codecs.put(type, codec);
        }
        return type.cast(codec);
    }
}
