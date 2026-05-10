// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reusable SBE decoder state intended to be owned by one event-loop thread.
 */
public final class SbeDecodeContext {
    private final MemorySegmentDirectBuffer buffer = new MemorySegmentDirectBuffer();
    private final Map<Class<?>, Object> flyweights = new HashMap<>();

    /**
     * Wraps a borrowed native payload segment for SBE decoding.
     *
     * @param segment the borrowed payload segment
     * @return the reusable read-only Agrona buffer
     */
    public MemorySegmentDirectBuffer wrap(MemorySegment segment) {
        return buffer.wrap(segment);
    }

    /**
     * Returns the reusable read-only Agrona buffer.
     *
     * @return the direct buffer
     */
    public MemorySegmentDirectBuffer buffer() {
        return buffer;
    }

    /**
     * Returns a reusable flyweight instance scoped to this context.
     *
     * @param type the flyweight type
     * @param factory factory used on first access
     * @param <T> the flyweight type
     * @return a context-scoped flyweight instance
     */
    public <T> T flyweight(Class<T> type, Supplier<? extends T> factory) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(factory, "factory");
        Object flyweight = flyweights.computeIfAbsent(type, ignored -> factory.get());
        return type.cast(flyweight);
    }
}
