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
            public void register(SerializerRegistry.Builder builder) {
                builder.encoder("module", String.class, encoder);
            }
        };

        // When
        SerializerRegistry registry = SerializerRegistry.builder()
                .encoder("text", String.class, encoder)
                .decoder("text", String.class, decoder)
                .flyweight("text", StringBuilder.class, flyweight)
                .module(module)
                .build();

        // Then
        assertThat(registry.encoder("text", String.class)).isSameAs(encoder);
        assertThat(registry.decoder("text", String.class)).isSameAs(decoder);
        assertThat(registry.flyweight("text", StringBuilder.class)).isSameAs(flyweight);
        assertThat(registry.encoder("module", String.class)).isSameAs(encoder);
        assertThat(SerializerRegistry.EMPTY.encoder("text", String.class)).isNull();
    }

    @Test
    void rejectsInvalidRegistryInputs() {
        // Given
        SerializerRegistry.Builder builder = SerializerRegistry.builder();

        // When / Then
        assertThatThrownBy(() -> builder.encoder("", String.class, new TestEncoder()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("serializer name must not be blank");
        assertThatThrownBy(() -> builder.encoder("text", null, new TestEncoder()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.decoder("text", null, new TestDecoder()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.flyweight("text", null, new TestFlyweightDecoder()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.module(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void fallsBackToAssignableRegistrationWhenExactTypeIsMissing() {
        // Given
        MessageEncoder<Object> genericEncoder = new GenericEncoder();
        MessageEncoder<String> typedEncoder = new TestEncoder();
        MessageDecoder<Object> genericDecoder = new GenericDecoder();
        MessageDecoder<String> typedDecoder = new TestDecoder();
        FlyweightDecoder<Object> genericFlyweight = new GenericFlyweightDecoder();
        FlyweightDecoder<StringBuilder> typedFlyweight = new TestFlyweightDecoder();

        SerializerRegistry registry = SerializerRegistry.builder()
                .encoder("sbe", Object.class, genericEncoder)
                .encoder("sbe", String.class, typedEncoder)
                .decoder("sbe", Object.class, genericDecoder)
                .decoder("sbe", String.class, typedDecoder)
                .flyweight("sbe", Object.class, genericFlyweight)
                .flyweight("sbe", StringBuilder.class, typedFlyweight)
                .build();

        // When / Then
        assertThat(registry.encoder("sbe", String.class)).isSameAs(typedEncoder);
        assertThat(registry.encoder("sbe", Integer.class)).isSameAs(genericEncoder);
        assertThat(registry.decoder("sbe", String.class)).isSameAs(typedDecoder);
        assertThat(registry.decoder("sbe", Integer.class)).isSameAs(genericDecoder);
        assertThat(registry.flyweight("sbe", StringBuilder.class)).isSameAs(typedFlyweight);
        assertThat(registry.flyweight("sbe", StringBuffer.class)).isSameAs(genericFlyweight);
    }

    @Test
    void copiesEntriesIntoAnotherBuilder() {
        // Given
        MessageEncoder<String> encoder = new TestEncoder();
        MessageDecoder<String> decoder = new TestDecoder();
        FlyweightDecoder<StringBuilder> flyweight = new TestFlyweightDecoder();
        SerializerRegistry source = SerializerRegistry.builder()
                .encoder("sbe", String.class, encoder)
                .decoder("sbe", String.class, decoder)
                .flyweight("sbe", StringBuilder.class, flyweight)
                .build();

        // When
        SerializerRegistry.Builder builder = SerializerRegistry.builder();
        source.registerInto(builder);
        SerializerRegistry copied = builder.build();

        // Then
        assertThat(copied.encoder("sbe", String.class)).isSameAs(encoder);
        assertThat(copied.decoder("sbe", String.class)).isSameAs(decoder);
        assertThat(copied.flyweight("sbe", StringBuilder.class)).isSameAs(flyweight);
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

    private static final class GenericEncoder implements MessageEncoder<Object> {

        @Override
        public int templateId() {
            return 8;
        }

        @Override
        public int encodedLength(Object value, EncodeContext context) {
            return 1;
        }

        @Override
        public int encode(Object value, WritableMessageBuffer target, EncodeContext context) {
            return 1;
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

    private static final class GenericDecoder implements MessageDecoder<Object> {

        @Override
        public int templateId() {
            return 8;
        }

        @Override
        public Object decode(ReadableMessageBuffer source, DecodeContext context) {
            return new Object();
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

    private static final class GenericFlyweightDecoder implements FlyweightDecoder<Object> {

        @Override
        public int templateId() {
            return 8;
        }

        @Override
        public Object wrap(MemorySegment payload, DecodeContext context) {
            return payload;
        }
    }
}
