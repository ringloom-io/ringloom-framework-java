// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.foreign.Arena;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class MemorySegmentDirectBufferTest {
    @Test
    void readsAndWritesPrimitiveValues() {
        // Given
        try (Arena arena = Arena.ofConfined()) {
            var segment = arena.allocate(16);
            MemorySegmentMutableDirectBuffer buffer = new MemorySegmentMutableDirectBuffer();
            buffer.wrap(segment);

            // When
            buffer.putLong(0, 42L, ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(8, 17, ByteOrder.BIG_ENDIAN);

            // Then
            assertThat(buffer.getLong(0, ByteOrder.LITTLE_ENDIAN)).isEqualTo(42L);
            assertThat(buffer.getInt(8, ByteOrder.BIG_ENDIAN)).isEqualTo(17);
        }
    }
}
