// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.fory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.framework.serialization.DecodeContext;
import io.ringloom.framework.serialization.EncodeContext;
import io.ringloom.framework.serialization.RingloomSerializationException;
import java.lang.foreign.Arena;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ForyMessageCodecTest {
    @Test
    void roundTripsRegisteredPojo() {
        // Given
        ForySerializerConfig config =
                new ForySerializerConfig(true, false, false, List.of(SampleMessage.class.getName()), 1024);
        ForyMessageCodec<SampleMessage> codec = new ForyMessageCodec<>(
                new ForySerializerModule().createFory(config), SampleMessage.class, config.maxPayloadBytes());
        SampleMessage message = new SampleMessage(42, "ringloom");
        EncodeContext encodeContext = new EncodeContext();
        DecodeContext decodeContext = new DecodeContext();

        // When
        int length = codec.encodedLength(message, encodeContext);
        try (Arena arena = Arena.ofConfined()) {
            var segment = arena.allocate(length);
            codec.encode(message, encodeContext.buffer().wrap(segment), encodeContext);
            SampleMessage decoded = codec.decode(decodeContext.buffer().wrap(segment), decodeContext);

            // Then
            assertThat(decoded).isEqualTo(message);
        }
    }

    @Test
    void decodesLengthLimitedPayloadFromLargerHeapAndNativeSegments() {
        // Given
        ForySerializerConfig config =
                new ForySerializerConfig(true, false, false, List.of(SampleMessage.class.getName()), 1024);
        ForyMessageCodec<SampleMessage> codec = new ForyMessageCodec<>(
                new ForySerializerModule().createFory(config), SampleMessage.class, config.maxPayloadBytes());
        SampleMessage message = new SampleMessage(42, "ringloom");
        EncodeContext encodeContext = new EncodeContext();
        DecodeContext decodeContext = new DecodeContext();
        int length = codec.encodedLength(message, encodeContext);

        try (Arena arena = Arena.ofConfined()) {
            var nativeScratch = arena.allocate(length + 64L);
            nativeScratch.fill((byte) 0x7F);
            var heapScratch = java.lang.foreign.MemorySegment.ofArray(new byte[length + 64]);
            heapScratch.fill((byte) 0x7F);

            // When
            int written = codec.encode(message, encodeContext.buffer().wrap(nativeScratch, length), encodeContext);
            java.lang.foreign.MemorySegment.copy(nativeScratch, 0, heapScratch, 0, length);
            SampleMessage fromNative = codec.decode(decodeContext.buffer().wrap(nativeScratch, length), decodeContext);
            SampleMessage fromHeap = codec.decode(decodeContext.buffer().wrap(heapScratch, length), decodeContext);

            // Then
            assertThat(written).isEqualTo(length);
            assertThat(nativeScratch.get(java.lang.foreign.ValueLayout.JAVA_BYTE, length))
                    .isEqualTo((byte) 0x7F);
            assertThat(fromNative).isEqualTo(message);
            assertThat(fromHeap).isEqualTo(message);
        }
    }

    @Test
    void rejectsEncodeIntoTooShortLengthLimitedTarget() {
        // Given
        ForySerializerConfig config =
                new ForySerializerConfig(true, false, false, List.of(SampleMessage.class.getName()), 1024);
        ForyMessageCodec<SampleMessage> codec = new ForyMessageCodec<>(
                new ForySerializerModule().createFory(config), SampleMessage.class, config.maxPayloadBytes());
        SampleMessage message = new SampleMessage(42, "ringloom");
        EncodeContext encodeContext = new EncodeContext();
        int length = codec.encodedLength(message, encodeContext);

        try (Arena arena = Arena.ofConfined()) {
            var scratch = arena.allocate(length + 64L);

            // When / Then
            assertThatThrownBy(() ->
                            codec.encode(message, encodeContext.buffer().wrap(scratch, length - 1L), encodeContext))
                    .isInstanceOf(RingloomSerializationException.class);
        }
    }

    @Test
    void roundTripsGeneratedTypesWithRequiredRegistration() {
        // Given
        ForySerializerConfig config = new ForySerializerConfig(true, false, false, List.of(), 1024);
        ForyMessageCodec<Object> codec = new ForyMessageCodec<>(
                new ForySerializerModule().createFory(config, List.of(NestedMessage.class, NestedSide.class)),
                Object.class,
                config.maxPayloadBytes());
        NestedMessage message = new NestedMessage(7, NestedSide.BUY);
        EncodeContext encodeContext = new EncodeContext();
        DecodeContext decodeContext = new DecodeContext();

        // When
        int length = codec.encodedLength(message, encodeContext);
        try (Arena arena = Arena.ofConfined()) {
            var segment = arena.allocate(length);
            codec.encode(message, encodeContext.buffer().wrap(segment), encodeContext);
            Object decoded = codec.decode(decodeContext.buffer().wrap(segment), decodeContext);

            // Then
            assertThat(decoded).isEqualTo(message);
        }
    }

    @Test
    void failsWhenRegistrationIsRequiredAndTypeIsMissing() {
        // Given
        ForySerializerConfig config = new ForySerializerConfig(true, false, false, List.of(), 1024);
        ForyMessageCodec<SampleMessage> codec = new ForyMessageCodec<>(
                new ForySerializerModule().createFory(config), SampleMessage.class, config.maxPayloadBytes());

        // When / Then
        assertThatThrownBy(() -> codec.encodedLength(new SampleMessage(7, "missing"), new EncodeContext()))
                .isInstanceOf(RingloomSerializationException.class);
    }

    @Test
    void rejectsPayloadsLargerThanConfiguredMaximum() {
        // Given
        ForySerializerConfig config =
                new ForySerializerConfig(true, false, false, List.of(SampleMessage.class.getName()), 8);
        ForyMessageCodec<SampleMessage> codec = new ForyMessageCodec<>(
                new ForySerializerModule().createFory(config), SampleMessage.class, config.maxPayloadBytes());

        // When / Then
        assertThatThrownBy(() -> codec.encodedLength(new SampleMessage(7, "too-large"), new EncodeContext()))
                .isInstanceOf(RingloomSerializationException.class);
    }

    public record SampleMessage(int id, String name) {}

    public record NestedMessage(int id, NestedSide side) {}

    public enum NestedSide {
        BUY,
        SELL
    }
}
