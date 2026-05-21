// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.tracing;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Compact payload prefix used for framework-managed trace-context propagation.
 */
public final class TracePayloadPrefix {
    private static final byte MAGIC_0 = 'R';
    private static final byte MAGIC_1 = 'L';
    private static final byte MAGIC_2 = 'T';
    private static final byte MAGIC_3 = 'P';
    private static final byte VERSION = 1;
    private static final int MAGIC_LENGTH = 4;
    private static final int VERSION_OFFSET = MAGIC_LENGTH;
    private static final int TRACEPARENT_LENGTH_OFFSET = VERSION_OFFSET + 1;
    private static final int TRACEPARENT_OFFSET = TRACEPARENT_LENGTH_OFFSET + 1;
    private static final int TRACEPARENT_LENGTH = 55;

    /**
     * Number of bytes reserved before the encoded application payload.
     */
    public static final int BYTE_LENGTH = TRACEPARENT_OFFSET + TRACEPARENT_LENGTH;

    private TracePayloadPrefix() {}

    /**
     * Writes a W3C traceparent into a payload prefix.
     *
     * @param prefix the target prefix segment
     * @param traceparent the W3C traceparent header value
     */
    public static void write(MemorySegment prefix, String traceparent) {
        if (prefix.byteSize() < BYTE_LENGTH) {
            throw new IllegalArgumentException("trace payload prefix segment is too small");
        }
        byte[] encoded = traceparent.getBytes(StandardCharsets.US_ASCII);
        if (encoded.length != TRACEPARENT_LENGTH) {
            throw new IllegalArgumentException("traceparent must be " + TRACEPARENT_LENGTH + " ASCII bytes");
        }
        prefix.set(ValueLayout.JAVA_BYTE, 0, MAGIC_0);
        prefix.set(ValueLayout.JAVA_BYTE, 1, MAGIC_1);
        prefix.set(ValueLayout.JAVA_BYTE, 2, MAGIC_2);
        prefix.set(ValueLayout.JAVA_BYTE, 3, MAGIC_3);
        prefix.set(ValueLayout.JAVA_BYTE, VERSION_OFFSET, VERSION);
        prefix.set(ValueLayout.JAVA_BYTE, TRACEPARENT_LENGTH_OFFSET, (byte) TRACEPARENT_LENGTH);
        MemorySegment.copy(
                MemorySegment.ofArray(encoded),
                ValueLayout.JAVA_BYTE,
                0,
                prefix,
                ValueLayout.JAVA_BYTE,
                TRACEPARENT_OFFSET,
                TRACEPARENT_LENGTH);
    }

    /**
     * Extracts a W3C traceparent from a prefixed payload.
     *
     * @param payload the received payload
     * @return the traceparent when the payload carries a supported prefix
     */
    public static Optional<String> traceparent(MemorySegment payload) {
        if (!hasPrefix(payload)) {
            return Optional.empty();
        }
        byte[] encoded = new byte[TRACEPARENT_LENGTH];
        MemorySegment.copy(
                payload,
                ValueLayout.JAVA_BYTE,
                TRACEPARENT_OFFSET,
                MemorySegment.ofArray(encoded),
                ValueLayout.JAVA_BYTE,
                0,
                TRACEPARENT_LENGTH);
        return Optional.of(new String(encoded, StandardCharsets.US_ASCII));
    }

    /**
     * Returns the application payload slice after the trace prefix.
     *
     * @param payload the received payload
     * @return the application payload without the prefix, or the original payload when no prefix is
     *     present
     */
    public static MemorySegment strip(MemorySegment payload) {
        return hasPrefix(payload) ? payload.asSlice(BYTE_LENGTH) : payload;
    }

    /**
     * Returns whether the payload begins with a supported trace prefix.
     *
     * @param payload the received payload
     * @return {@code true} when the prefix is present
     */
    public static boolean hasPrefix(MemorySegment payload) {
        return payload.byteSize() >= BYTE_LENGTH
                && payload.get(ValueLayout.JAVA_BYTE, 0) == MAGIC_0
                && payload.get(ValueLayout.JAVA_BYTE, 1) == MAGIC_1
                && payload.get(ValueLayout.JAVA_BYTE, 2) == MAGIC_2
                && payload.get(ValueLayout.JAVA_BYTE, 3) == MAGIC_3
                && payload.get(ValueLayout.JAVA_BYTE, VERSION_OFFSET) == VERSION
                && Byte.toUnsignedInt(payload.get(ValueLayout.JAVA_BYTE, TRACEPARENT_LENGTH_OFFSET))
                        == TRACEPARENT_LENGTH;
    }
}
