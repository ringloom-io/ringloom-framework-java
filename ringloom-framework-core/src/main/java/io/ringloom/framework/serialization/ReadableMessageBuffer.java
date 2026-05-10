// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

import java.lang.foreign.MemorySegment;

/**
 * Read-only view over an encoded RingLoom message payload.
 */
public interface ReadableMessageBuffer {
    MemorySegment segment();

    long length();
}
