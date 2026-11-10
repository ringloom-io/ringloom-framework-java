// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.framework.serialization.DecodeContext;
import io.ringloom.framework.serialization.EncodeContext;
import io.ringloom.framework.serialization.MemorySegmentMessageBuffer;
import io.ringloom.framework.serialization.MessageDecoder;
import io.ringloom.framework.serialization.MessageEncoder;
import io.ringloom.framework.serialization.ReadableMessageBuffer;
import io.ringloom.framework.serialization.WritableMessageBuffer;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

final class SerializerKvKeyCodecTest {
    /** Encodes a pair of ints as 8 bytes; decoder asserts it sees exactly 8 bytes. */
    record Pair(int a, int b) {}

    private static final MessageEncoder<Pair> ENCODER = new MessageEncoder<>() {
        @Override
        public int templateId() {
            return 1;
        }

        @Override
        public int encodedLength(Pair value, EncodeContext context) {
            return 8;
        }

        @Override
        public int encode(Pair value, WritableMessageBuffer target, EncodeContext context) {
            MemorySegmentMessageBuffer buffer = (MemorySegmentMessageBuffer) target;
            buffer.putInt(0, value.a());
            buffer.putInt(4, value.b());
            return 8;
        }
    };

    private static final MessageDecoder<Pair> DECODER = new MessageDecoder<>() {
        @Override
        public int templateId() {
            return 1;
        }

        @Override
        public Pair decode(ReadableMessageBuffer source, DecodeContext context) {
            assertThat(source.length()).isEqualTo(8);
            MemorySegmentMessageBuffer buffer = (MemorySegmentMessageBuffer) source;
            return new Pair(buffer.getInt(0), buffer.getInt(4));
        }
    };

    @Test
    void roundTripsThroughSerializer() {
        KvKeyCodec<Pair> codec = KvKeyCodecs.serializer(ENCODER, DECODER);
        MemorySegment scratch = MemorySegment.ofArray(new byte[32]);

        int written = codec.encode(new Pair(3, -4), scratch, 32);

        assertThat(written).isEqualTo(8);
        assertThat(codec.decode(scratch, 0, 8)).isEqualTo(new Pair(3, -4));
        MemorySegment.copy(scratch, 0, scratch, 5, 8);
        assertThat(codec.decode(scratch, 5, 8)).isEqualTo(new Pair(3, -4));
    }

    @Test
    void encoderSeesLengthLimitedBuffer() {
        MessageEncoder<Pair> overflowing = new MessageEncoder<>() {
            @Override
            public int templateId() {
                return 1;
            }

            @Override
            public int encodedLength(Pair value, EncodeContext context) {
                return 4;
            }

            @Override
            public int encode(Pair value, WritableMessageBuffer target, EncodeContext context) {
                ((MemorySegmentMessageBuffer) target).putLong(0, 1L);
                return 8;
            }
        };
        KvKeyCodec<Pair> codec = new SerializerKvKeyCodec<>(overflowing, null);

        assertThatThrownBy(() -> codec.encode(new Pair(1, 2), MemorySegment.ofArray(new byte[32]), 32))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> codec.decode(MemorySegment.ofArray(new byte[8]), 0, 8))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void returnsDoesNotFitWhenCapacityTooSmall() {
        KvKeyCodec<Pair> codec = KvKeyCodecs.serializer(ENCODER, DECODER);

        assertThat(codec.encode(new Pair(1, 2), MemorySegment.ofArray(new byte[32]), 7))
                .isEqualTo(KvKeyCodec.DOES_NOT_FIT);
        assertThat(codec.encode(new Pair(1, 2), MemorySegment.ofArray(new byte[7]), 32))
                .isEqualTo(KvKeyCodec.DOES_NOT_FIT);
    }
}
