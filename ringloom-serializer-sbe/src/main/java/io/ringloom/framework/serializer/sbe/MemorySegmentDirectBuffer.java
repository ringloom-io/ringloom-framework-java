// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Reusable Agrona direct buffer view over native {@link MemorySegment} memory for SBE codecs.
 *
 * <p>The wrapped segment is borrowed; callers must not retain this adapter beyond the lifetime of
 * the segment owner, such as a RingLoom message callback or active buffer claim.
 */
public class MemorySegmentDirectBuffer extends UnsafeBuffer {
    private MemorySegment segment = MemorySegment.NULL;

    public MemorySegmentDirectBuffer() {
        super(0L, 0);
    }

    /**
     * Wraps a native memory segment without copying.
     *
     * @param segment the native segment to expose as an Agrona buffer
     * @return this reusable adapter
     */
    public MemorySegmentDirectBuffer wrap(MemorySegment segment) {
        Objects.requireNonNull(segment, "segment");
        return wrap(segment, segment.byteSize());
    }

    /**
     * Wraps the first {@code length} bytes of a native memory segment without copying or slicing.
     * Agrona bounds checks then reject access past {@code length}.
     *
     * @param segment the native segment to expose as an Agrona buffer; may be larger than
     *     {@code length}
     * @param length the number of bytes to expose, in {@code [0, segment.byteSize()]}
     * @return this reusable adapter
     */
    public MemorySegmentDirectBuffer wrap(MemorySegment segment, long length) {
        Objects.requireNonNull(segment, "segment");
        if (!segment.isNative()) {
            throw new IllegalArgumentException("SBE MemorySegment buffers require native memory");
        }
        if (length < 0 || length > segment.byteSize()) {
            throw new IndexOutOfBoundsException(
                    "length " + length + " outside segment of " + segment.byteSize() + " bytes");
        }
        this.segment = segment;
        super.wrap(segment.address(), Math.toIntExact(length));
        return this;
    }

    /**
     * Returns the currently wrapped segment.
     *
     * @return the borrowed segment
     */
    public MemorySegment segment() {
        return segment;
    }
}
