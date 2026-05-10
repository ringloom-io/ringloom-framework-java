// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

/**
 * Reusable scratch state for message decoders.
 */
public final class DecodeContext {
    private final MemorySegmentMessageBuffer buffer = new MemorySegmentMessageBuffer();

    public MemorySegmentMessageBuffer buffer() {
        return buffer;
    }
}
