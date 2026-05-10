// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Read-only direct buffer backed by a {@link MemorySegment} for SBE codecs.
 */
public class MemorySegmentDirectBuffer {
    protected MemorySegment segment = MemorySegment.NULL;

    public MemorySegmentDirectBuffer wrap(MemorySegment segment) {
        this.segment = Objects.requireNonNull(segment, "segment");
        return this;
    }

    public byte getByte(long index) {
        return segment.get(ValueLayout.JAVA_BYTE, index);
    }

    public short getShort(long index, ByteOrder order) {
        return segment.get(ValueLayout.JAVA_SHORT.withOrder(order), index);
    }

    public int getInt(long index, ByteOrder order) {
        return segment.get(ValueLayout.JAVA_INT.withOrder(order), index);
    }

    public long getLong(long index, ByteOrder order) {
        return segment.get(ValueLayout.JAVA_LONG.withOrder(order), index);
    }

    public long capacity() {
        return segment.byteSize();
    }
}
