// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

import java.lang.foreign.MemorySegment;

/**
 * Reusable scratch state for message decoders.
 *
 * <p>{@link #payloadLength()} lets callers hand a {@link FlyweightDecoder} a scratch segment that
 * is larger than the payload without slicing. Callers that pass an exact-size segment leave it
 * unset ({@link #UNSET_PAYLOAD_LENGTH}).
 */
public final class DecodeContext {
    /** Sentinel meaning "no explicit length; the whole payload segment is the payload". */
    public static final long UNSET_PAYLOAD_LENGTH = -1L;

    private final MemorySegmentMessageBuffer buffer = new MemorySegmentMessageBuffer();
    private long payloadLength = UNSET_PAYLOAD_LENGTH;

    public MemorySegmentMessageBuffer buffer() {
        return buffer;
    }

    /**
     * Returns the explicit payload length set by the caller, or {@link #UNSET_PAYLOAD_LENGTH}.
     *
     * @return the payload length or {@link #UNSET_PAYLOAD_LENGTH}
     */
    public long payloadLength() {
        return payloadLength;
    }

    /**
     * Sets the payload length for the next flyweight wrap or decode.
     *
     * @param payloadLength a non-negative length, or {@link #UNSET_PAYLOAD_LENGTH} to clear
     * @return this context
     */
    public DecodeContext payloadLength(long payloadLength) {
        if (payloadLength < 0 && payloadLength != UNSET_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("payloadLength must be >= 0 or UNSET_PAYLOAD_LENGTH");
        }
        this.payloadLength = payloadLength;
        return this;
    }

    /**
     * Resolves the authoritative payload length for {@code payload}: the explicit
     * {@link #payloadLength()} when set, otherwise {@code payload.byteSize()}.
     *
     * @param payload the segment handed to the decoder
     * @return the payload length to respect
     * @throws IndexOutOfBoundsException if the explicit length exceeds the segment
     */
    public long payloadLength(MemorySegment payload) {
        long size = payload.byteSize();
        if (payloadLength == UNSET_PAYLOAD_LENGTH) {
            return size;
        }
        if (payloadLength > size) {
            throw new IndexOutOfBoundsException("payloadLength " + payloadLength + " exceeds segment of " + size);
        }
        return payloadLength;
    }

    /**
     * Null-safe variant of {@link #payloadLength(MemorySegment)}.
     *
     * @param context the decode context, possibly {@code null}
     * @param payload the payload segment
     * @return the payload length to respect
     */
    public static long payloadLength(DecodeContext context, MemorySegment payload) {
        return context == null ? payload.byteSize() : context.payloadLength(payload);
    }
}
