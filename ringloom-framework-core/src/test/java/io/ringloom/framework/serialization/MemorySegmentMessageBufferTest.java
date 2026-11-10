// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

final class MemorySegmentMessageBufferTest {
    @Test
    void wrapWithoutLengthUsesWholeSegment() {
        MemorySegment segment = MemorySegment.ofArray(new byte[16]);

        MemorySegmentMessageBuffer buffer = new MemorySegmentMessageBuffer().wrap(segment);

        assertThat(buffer.length()).isEqualTo(16);
        assertThat(buffer.segment()).isSameAs(segment);
        assertThat(new MemorySegmentMessageBuffer(segment).length()).isEqualTo(16);
        assertThat(new MemorySegmentMessageBuffer().length()).isZero();
    }

    @Test
    void lengthLimitedBufferRejectsReadsAndWritesPastLimit() {
        MemorySegment segment =
                MemorySegment.ofArray(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});

        MemorySegmentMessageBuffer buffer = new MemorySegmentMessageBuffer().wrap(segment, 5);

        assertThat(buffer.length()).isEqualTo(5);
        assertThat(buffer.segment()).isSameAs(segment);
        assertThat(buffer.getByte(4)).isEqualTo((byte) 5);
        assertThat(buffer.toByteArray()).containsExactly(1, 2, 3, 4, 5);
        assertThat(buffer.getInt(1)).isEqualTo(segment.get(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 1));
        assertThatThrownBy(() -> buffer.getByte(5)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> buffer.getByte(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> buffer.getInt(2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> buffer.getLong(0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> buffer.putByte(5, (byte) 0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> buffer.putLong(0, 1L)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> buffer.getBytes(2, new byte[4], 0, 4)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> buffer.putBytes(0, new byte[6], 0, 6)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> buffer.checkBounds(Long.MAX_VALUE, 2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 5)).isEqualTo((byte) 6);
    }

    @Test
    void lengthLimitedBufferWritesWithinLimit() {
        MemorySegment segment = MemorySegment.ofArray(new byte[16]);
        MemorySegmentMessageBuffer buffer = new MemorySegmentMessageBuffer().wrap(segment, 12);

        buffer.putLong(4, 0x0102030405060708L);
        buffer.putBytes(0, new byte[] {9, 9}, 0, 2);
        byte[] out = new byte[2];
        buffer.getBytes(0, out, 0, 2);

        assertThat(buffer.getLong(4)).isEqualTo(0x0102030405060708L);
        assertThat(out).containsExactly(9, 9);
    }

    @Test
    void wrapRejectsInvalidLength() {
        MemorySegment segment = MemorySegment.ofArray(new byte[8]);
        MemorySegmentMessageBuffer buffer = new MemorySegmentMessageBuffer();

        assertThatThrownBy(() -> buffer.wrap(segment, 9)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> buffer.wrap(segment, -1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(buffer.wrap(segment, 0).length()).isZero();
        assertThat(buffer.wrap(segment, 8).length()).isEqualTo(8);
    }

    @Test
    void decodeContextPayloadLength() {
        DecodeContext context = new DecodeContext();
        MemorySegment segment = MemorySegment.ofArray(new byte[32]);

        assertThat(context.payloadLength()).isEqualTo(DecodeContext.UNSET_PAYLOAD_LENGTH);
        assertThat(context.payloadLength(segment)).isEqualTo(32);
        assertThat(DecodeContext.payloadLength(null, segment)).isEqualTo(32);
        assertThat(context.payloadLength(10).payloadLength()).isEqualTo(10);
        assertThat(context.payloadLength(segment)).isEqualTo(10);
        assertThat(DecodeContext.payloadLength(context, segment)).isEqualTo(10);
        assertThatThrownBy(() -> context.payloadLength(-2)).isInstanceOf(IllegalArgumentException.class);
        context.payloadLength(33);
        assertThatThrownBy(() -> context.payloadLength(segment)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(context.payloadLength(DecodeContext.UNSET_PAYLOAD_LENGTH).payloadLength(segment))
                .isEqualTo(32);
    }
}
