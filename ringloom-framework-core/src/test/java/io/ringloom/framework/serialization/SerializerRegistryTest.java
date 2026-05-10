// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.framework.status.RingloomHandlerStatus;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class SerializerRegistryTest {
    @Test
    void registersEncodersDecodersFlyweightsAndModules() {
        // Given
        MessageEncoder<String> encoder = new TestEncoder();
        MessageDecoder<String> decoder = new TestDecoder();
        FlyweightDecoder<StringBuilder> flyweight = new TestFlyweightDecoder();
        SerializerModule module = new SerializerModule() {
            @Override
            public String name() {
                return "module";
            }

            @Override
            public void register(SerializerRegistry.Builder builder) {
                builder.encoder("module", encoder);
            }
        };

        // When
        SerializerRegistry registry = SerializerRegistry.builder()
                .encoder("text", encoder)
                .decoder("text", decoder)
                .flyweight("text", flyweight)
                .module(module)
                .build();

        // Then
        assertThat(registry.contains("text")).isTrue();
        assertThat(registry.contains("module")).isTrue();
        assertThat(registry.encoder("text", String.class)).isSameAs(encoder);
        assertThat(registry.decoder("text", String.class)).isSameAs(decoder);
        assertThat(registry.flyweight("text", StringBuilder.class)).isSameAs(flyweight);
        assertThat(SerializerRegistry.EMPTY.contains("text")).isFalse();
    }

    @Test
    void rejectsInvalidRegistryInputs() {
        // Given
        SerializerRegistry.Builder builder = SerializerRegistry.builder();

        // When / Then
        assertThatThrownBy(() -> builder.encoder("", new TestEncoder()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("serializer name must not be blank");
        assertThatThrownBy(() -> builder.decoder("text", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.flyweight("text", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.module(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void wrapsMemorySegmentsInReusableContexts() {
        // Given
        EncodeContext encodeContext = new EncodeContext();
        DecodeContext decodeContext = new DecodeContext();

        // When
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(8);
            MemorySegmentMessageBuffer encoded = encodeContext.buffer().wrap(segment);
            MemorySegmentMessageBuffer decoded = decodeContext.buffer().wrap(segment);

            // Then
            assertThat(encoded).isSameAs(encodeContext.buffer());
            assertThat(decoded).isSameAs(decodeContext.buffer());
            assertThat(encoded.segment()).isSameAs(segment);
            assertThat(decoded.length()).isEqualTo(8);
        }
    }

    @Test
    void exposesSerializationExceptionStatusAndCause() {
        // Given
        RuntimeException cause = new RuntimeException("boom");

        // When
        RingloomSerializationException exception =
                new RingloomSerializationException("failed", RingloomHandlerStatus.NATIVE_SYMBOL_UNAVAILABLE, cause);

        // Then
        assertThat(exception).hasMessage("failed").hasCause(cause);
        assertThat(exception.status()).isEqualTo(RingloomHandlerStatus.NATIVE_SYMBOL_UNAVAILABLE);
        assertThat(new RingloomSerializationException("failed").status())
                .isEqualTo(RingloomHandlerStatus.SERIALIZATION_ERROR);
    }

    @Test
    void invokesFunctionalSerializerContracts() {
        // Given
        TestEncoder encoder = new TestEncoder();
        TestDecoder decoder = new TestDecoder();
        AtomicBoolean wrapped = new AtomicBoolean();
        FlyweightDecoder<Object> flyweight = new FlyweightDecoder<>() {
            @Override
            public int templateId() {
                return 7;
            }

            @Override
            public Object wrap(MemorySegment payload, DecodeContext context) {
                wrapped.set(true);
                return payload;
            }
        };

        // When
        int length = encoder.encodedLength("ringloom", new EncodeContext());
        String value = decoder.decode(new MemorySegmentMessageBuffer(MemorySegment.NULL), new DecodeContext());
        Object result = flyweight.wrap(MemorySegment.NULL, new DecodeContext());

        // Then
        assertThat(length).isEqualTo(8);
        assertThat(value).isEqualTo("decoded");
        assertThat(result).isSameAs(MemorySegment.NULL);
        assertThat(wrapped).isTrue();
    }

    private static final class TestEncoder implements MessageEncoder<String> {
        @Override
        public int templateId() {
            return 7;
        }

        @Override
        public int encodedLength(String value, EncodeContext context) {
            return value.length();
        }

        @Override
        public int encode(String value, WritableMessageBuffer target, EncodeContext context) {
            return value.length();
        }
    }

    private static final class TestDecoder implements MessageDecoder<String> {
        @Override
        public int templateId() {
            return 7;
        }

        @Override
        public String decode(ReadableMessageBuffer source, DecodeContext context) {
            return "decoded";
        }
    }

    private static final class TestFlyweightDecoder implements FlyweightDecoder<StringBuilder> {
        @Override
        public int templateId() {
            return 7;
        }

        @Override
        public StringBuilder wrap(MemorySegment payload, DecodeContext context) {
            return new StringBuilder("wrapped");
        }
    }
}
