// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

import java.lang.foreign.Arena;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MemorySegmentDirectBufferTest {
    @Test
    void readsAndWritesPrimitiveValues() {
        try (Arena arena = Arena.ofConfined()) {
            var segment = arena.allocate(16);
            MemorySegmentMutableDirectBuffer buffer = new MemorySegmentMutableDirectBuffer();
            buffer.wrap(segment);

            buffer.putLong(0, 42L, ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(8, 17, ByteOrder.BIG_ENDIAN);

            assertEquals(42L, buffer.getLong(0, ByteOrder.LITTLE_ENDIAN));
            assertEquals(17, buffer.getInt(8, ByteOrder.BIG_ENDIAN));
        }
    }
}
