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
}
