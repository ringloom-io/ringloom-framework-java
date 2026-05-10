// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

import java.lang.foreign.MemorySegment;

public interface ReadableMessageBuffer {
    MemorySegment segment();

    long length();
}
