// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

/**
 * Mutable extension of {@link MemorySegmentDirectBuffer} for SBE encoders.
 */
public final class MemorySegmentMutableDirectBuffer extends MemorySegmentDirectBuffer {
    @Override
    public MemorySegmentMutableDirectBuffer wrap(java.lang.foreign.MemorySegment segment) {
        super.wrap(segment);
        return this;
    }

    @Override
    public MemorySegmentMutableDirectBuffer wrap(java.lang.foreign.MemorySegment segment, long length) {
        super.wrap(segment, length);
        return this;
    }
}
