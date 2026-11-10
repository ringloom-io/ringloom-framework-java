// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.framework.serialization.DecodeContext;
import io.ringloom.framework.serialization.EncodeContext;
import io.ringloom.framework.serialization.MemorySegmentMessageBuffer;
import java.lang.foreign.Arena;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class LengthLimitedSbeBufferTest {
    @Test
    void lengthLimitedWrapRejectsAccessPastLimit() {
        try (Arena arena = Arena.ofConfined()) {
            var segment = arena.allocate(64);
            MemorySegmentMutableDirectBuffer buffer = new MemorySegmentMutableDirectBuffer().wrap(segment, 12);

            buffer.putLong(4, 7L, ByteOrder.LITTLE_ENDIAN);

            assertThat(buffer.capacity()).isEqualTo(12);
            assertThat(buffer.getLong(4, ByteOrder.LITTLE_ENDIAN)).isEqualTo(7L);
            assertThat(buffer.segment()).isSameAs(segment);
            assertThatThrownBy(() -> buffer.getLong(8, ByteOrder.LITTLE_ENDIAN))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> buffer.putByte(12, (byte) 1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> buffer.wrap(segment, 65)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Test
    void flyweightDecoderRespectsPayloadLength() {
        SbeFlyweightDecoder<Holder> decoder = new SbeFlyweightDecoder<>(1, Holder.class, Holder::new, (h, b, c) -> {
            h.capacity = b.capacity();
            return h;
        });
        DecodeContext context = new DecodeContext();
        try (Arena arena = Arena.ofConfined()) {
            var scratch = arena.allocate(256);

            assertThat(decoder.wrap(scratch, context).capacity).isEqualTo(256);
            assertThat(decoder.wrap(scratch, null).capacity).isEqualTo(256);
            assertThat(decoder.wrap(scratch, context.payloadLength(20)).capacity)
                    .isEqualTo(20);
            assertThat(decoder.wrap(scratch, context.payloadLength(DecodeContext.UNSET_PAYLOAD_LENGTH)).capacity)
                    .isEqualTo(256);
        }
    }

    @Test
    void dtoDecoderAndEncoderRespectBufferLength() {
        SbeDtoMessageDecoder<Holder, Holder> decoder = new SbeDtoMessageDecoder<>(
                1,
                Holder.class,
                Holder::new,
                Holder.class,
                Holder::new,
                (d, b, c) -> {
                    d.capacity = b.capacity();
                    return d;
                },
                (d, dto) -> dto.capacity = d.capacity);
        SbeMessageEncoder<Long> encoder =
                new SbeMessageEncoder<>(1, (v, c) -> Long.BYTES, (v, target, c) -> target.capacity());
        try (Arena arena = Arena.ofConfined()) {
            var scratch = arena.allocate(256);
            DecodeContext decodeContext = new DecodeContext();
            EncodeContext encodeContext = new EncodeContext();

            Holder decoded = decoder.decode(decodeContext.buffer().wrap(scratch, 30), decodeContext);
            int encodeCapacity = encoder.encode(1L, encodeContext.buffer().wrap(scratch, 8), encodeContext);
            int fullCapacity = encoder.encode(1L, new MemorySegmentMessageBuffer(scratch), encodeContext);

            assertThat(decoded.capacity).isEqualTo(30);
            assertThat(encodeCapacity).isEqualTo(8);
            assertThat(fullCapacity).isEqualTo(256);
        }
    }

    static final class Holder {
        int capacity;
    }
}
