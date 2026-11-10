// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * Message buffer backed by a {@link MemorySegment}.
 *
 * <p>The buffer may be <em>length-limited</em>: {@link #wrap(MemorySegment, long)} exposes only the
 * first {@code length} bytes of a (possibly larger) segment without allocating an {@code asSlice}
 * view. {@link #length()} returns that limit, and the accessor methods on this class throw
 * {@link IndexOutOfBoundsException} for any access past it. {@link #segment()} still returns the
 * whole underlying segment, so codecs that access it directly must bound their reads and writes by
 * {@link #length()} rather than {@link MemorySegment#byteSize()}.
 */
public final class MemorySegmentMessageBuffer implements ReadableMessageBuffer, WritableMessageBuffer {
    private MemorySegment segment;
    private long length;

    public MemorySegmentMessageBuffer() {
        this.segment = MemorySegment.NULL;
        this.length = 0L;
    }

    public MemorySegmentMessageBuffer(MemorySegment segment) {
        wrap(segment);
    }

    /**
     * Wraps the whole segment; {@link #length()} equals {@code segment.byteSize()}.
     *
     * @param segment the segment to wrap
     * @return this buffer
     */
    public MemorySegmentMessageBuffer wrap(MemorySegment segment) {
        this.segment = Objects.requireNonNull(segment, "segment");
        this.length = segment.byteSize();
        return this;
    }

    /**
     * Wraps the first {@code length} bytes of {@code segment} without slicing.
     *
     * @param segment the segment to wrap; may be larger than {@code length}
     * @param length the payload length, in {@code [0, segment.byteSize()]}
     * @return this buffer
     * @throws IndexOutOfBoundsException if {@code length} is negative or larger than the segment
     */
    public MemorySegmentMessageBuffer wrap(MemorySegment segment, long length) {
        Objects.requireNonNull(segment, "segment");
        if (length < 0 || length > segment.byteSize()) {
            throw new IndexOutOfBoundsException(
                    "length " + length + " outside segment of " + segment.byteSize() + " bytes");
        }
        this.segment = segment;
        this.length = length;
        return this;
    }

    /**
     * Returns the underlying segment. It may be larger than {@link #length()}; only the first
     * {@link #length()} bytes belong to the payload.
     */
    @Override
    public MemorySegment segment() {
        return segment;
    }

    /** Returns the payload length limit. */
    @Override
    public long length() {
        return length;
    }

    /**
     * Checks that {@code [offset, offset + count)} lies within the payload limit.
     *
     * @param offset start offset
     * @param count number of bytes
     * @throws IndexOutOfBoundsException if the range is outside {@code [0, length())}
     */
    public void checkBounds(long offset, long count) {
        if (offset < 0 || count < 0 || offset > length - count) {
            throw new IndexOutOfBoundsException(
                    "range [" + offset + ", " + offset + " + " + count + ") outside buffer length " + length);
        }
    }

    public byte getByte(long offset) {
        checkBounds(offset, 1);
        return segment.get(ValueLayout.JAVA_BYTE, offset);
    }

    public void putByte(long offset, byte value) {
        checkBounds(offset, 1);
        segment.set(ValueLayout.JAVA_BYTE, offset, value);
    }

    /** Reads an unaligned native-order int. */
    public int getInt(long offset) {
        checkBounds(offset, Integer.BYTES);
        return segment.get(ValueLayout.JAVA_INT_UNALIGNED, offset);
    }

    /** Writes an unaligned native-order int. */
    public void putInt(long offset, int value) {
        checkBounds(offset, Integer.BYTES);
        segment.set(ValueLayout.JAVA_INT_UNALIGNED, offset, value);
    }

    /** Reads an unaligned native-order long. */
    public long getLong(long offset) {
        checkBounds(offset, Long.BYTES);
        return segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
    }

    /** Writes an unaligned native-order long. */
    public void putLong(long offset, long value) {
        checkBounds(offset, Long.BYTES);
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED, offset, value);
    }

    /** Copies {@code count} payload bytes starting at {@code offset} into {@code dst}. */
    public void getBytes(long offset, byte[] dst, int dstOffset, int count) {
        checkBounds(offset, count);
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, dst, dstOffset, count);
    }

    /** Copies {@code count} bytes from {@code src} into the payload starting at {@code offset}. */
    public void putBytes(long offset, byte[] src, int srcOffset, int count) {
        checkBounds(offset, count);
        MemorySegment.copy(src, srcOffset, segment, ValueLayout.JAVA_BYTE, offset, count);
    }

    /** Returns a copy of the payload bytes ({@link #length()} bytes, not the whole segment). */
    public byte[] toByteArray() {
        byte[] bytes = new byte[Math.toIntExact(length)];
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, 0, bytes, 0, bytes.length);
        return bytes;
    }
}
