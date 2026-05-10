// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

import java.lang.foreign.MemorySegment;

/**
 * Writable target used by message encoders.
 */
public interface WritableMessageBuffer {
    MemorySegment segment();

    long length();
}
