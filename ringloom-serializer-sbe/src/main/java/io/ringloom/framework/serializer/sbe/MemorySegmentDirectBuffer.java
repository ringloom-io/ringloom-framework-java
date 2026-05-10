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
        this.segment = Objects.requireNonNull(segment, "segment");
        if (!segment.isNative()) {
            throw new IllegalArgumentException("SBE MemorySegment buffers require native memory");
        }
        super.wrap(segment.address(), Math.toIntExact(segment.byteSize()));
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
