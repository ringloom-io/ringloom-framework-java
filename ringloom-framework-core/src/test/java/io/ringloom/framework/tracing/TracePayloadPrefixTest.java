// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

final class TracePayloadPrefixTest {
    @Test
    void writesAndStripsTraceparentPrefix() {
        // Given
        String traceparent = "00-00000000000000000000000000000001-0000000000000002-01";
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment payload = arena.allocate(TracePayloadPrefix.BYTE_LENGTH + 4);
            payload.set(ValueLayout.JAVA_BYTE, TracePayloadPrefix.BYTE_LENGTH, (byte) 7);

            // When
            TracePayloadPrefix.write(payload.asSlice(0, TracePayloadPrefix.BYTE_LENGTH), traceparent);

            // Then
            assertThat(TracePayloadPrefix.hasPrefix(payload)).isTrue();
            assertThat(TracePayloadPrefix.traceparent(payload)).contains(traceparent);
            MemorySegment stripped = TracePayloadPrefix.strip(payload);
            assertThat(stripped.byteSize()).isEqualTo(4);
            assertThat(stripped.get(ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) 7);
        }
    }

    @Test
    void ignoresPayloadsWithoutPrefix() {
        // Given
        MemorySegment payload = MemorySegment.ofArray(new byte[TracePayloadPrefix.BYTE_LENGTH]);

        // When / Then
        assertThat(TracePayloadPrefix.hasPrefix(payload)).isFalse();
        assertThat(TracePayloadPrefix.traceparent(payload)).isEmpty();
        assertThat(TracePayloadPrefix.strip(payload)).isSameAs(payload);
    }

    @Test
    void rejectsInvalidTraceparentLength() {
        // Given
        MemorySegment prefix = MemorySegment.ofArray(new byte[TracePayloadPrefix.BYTE_LENGTH]);

        // When / Then
        assertThatThrownBy(() -> TracePayloadPrefix.write(prefix, "invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("traceparent must be 55 ASCII bytes");
    }
}
