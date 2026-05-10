// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Message buffer backed by a {@link MemorySegment}.
 */
public final class MemorySegmentMessageBuffer implements ReadableMessageBuffer, WritableMessageBuffer {
    private MemorySegment segment;

    public MemorySegmentMessageBuffer() {
        this.segment = MemorySegment.NULL;
    }

    public MemorySegmentMessageBuffer(MemorySegment segment) {
        this.segment = Objects.requireNonNull(segment, "segment");
    }

    public MemorySegmentMessageBuffer wrap(MemorySegment segment) {
        this.segment = Objects.requireNonNull(segment, "segment");
        return this;
    }

    @Override
    public MemorySegment segment() {
        return segment;
    }

    @Override
    public long length() {
        return segment.byteSize();
    }
}
