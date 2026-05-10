// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.fory;

import io.ringloom.framework.serialization.RingloomSerializationException;
import java.util.Objects;
import org.apache.fory.memory.MemoryBuffer;

/**
 * Reusable encode state for Apache Fory serializers.
 */
public final class ForyEncodeContext {
    private final int maxPayloadBytes;
    private MemoryBuffer buffer;
    private Object value;
    private int length;

    public ForyEncodeContext(int maxPayloadBytes) {
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        this.maxPayloadBytes = maxPayloadBytes;
        this.buffer = MemoryBuffer.newHeapBuffer(Math.min(maxPayloadBytes, 4096));
    }

    /**
     * Stores a prepared encoded payload for a subsequent copy into RingLoom memory.
     *
     * @param value the value that produced the payload
     * @param buffer the encoded Fory buffer
     */
    public void prepare(Object value, MemoryBuffer buffer) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        int encodedLength = buffer.writerIndex();
        if (encodedLength > maxPayloadBytes) {
            throw new RingloomSerializationException(
                    "Fory payload length " + encodedLength + " exceeds maxPayloadBytes " + maxPayloadBytes);
        }
        this.value = value;
        this.length = encodedLength;
    }

    /**
     * Returns whether this context already contains bytes for the supplied value instance.
     *
     * @param candidate the value to check
     * @return true when the encoded bytes match the same value instance
     */
    public boolean matches(Object candidate) {
        return value == candidate && length > 0;
    }

    /**
     * Returns the reusable Fory memory buffer.
     *
     * @return the reusable memory buffer
     */
    public MemoryBuffer buffer() {
        return buffer;
    }

    /**
     * Returns the encoded heap memory.
     *
     * @return the encoded bytes
     */
    public byte[] heapMemory() {
        return buffer.getHeapMemory();
    }

    /**
     * Returns the encoded payload length.
     *
     * @return the encoded length
     */
    public int length() {
        return length;
    }

    /**
     * Clears the value association while retaining the encoded byte storage reference.
     */
    public void reset() {
        value = null;
        length = 0;
        buffer.readerIndex(0);
        buffer.writerIndex(0);
    }
}
