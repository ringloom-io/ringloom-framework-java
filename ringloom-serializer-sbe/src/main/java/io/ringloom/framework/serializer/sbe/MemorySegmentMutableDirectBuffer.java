// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Mutable extension of {@link MemorySegmentDirectBuffer} for SBE encoders.
 */
public final class MemorySegmentMutableDirectBuffer extends MemorySegmentDirectBuffer {
    public void putByte(long index, byte value) {
        segment.set(ValueLayout.JAVA_BYTE, index, value);
    }

    public void putShort(long index, short value, ByteOrder order) {
        segment.set(ValueLayout.JAVA_SHORT.withOrder(order), index, value);
    }

    public void putInt(long index, int value, ByteOrder order) {
        segment.set(ValueLayout.JAVA_INT.withOrder(order), index, value);
    }

    public void putLong(long index, long value, ByteOrder order) {
        segment.set(ValueLayout.JAVA_LONG.withOrder(order), index, value);
    }
}
