// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import java.lang.foreign.MemorySegment;

/**
 * Visitor for KV {@code forEach}. Keys are passed as raw bytes so iteration never boxes; decode them
 * with {@link io.ringloom.framework.kv.codec.KvKeyCodecs#decodeLong(MemorySegment)} and similar helpers.
 *
 * @param <V> the value type
 */
@FunctionalInterface
public interface KvEntryVisitor<V> {
    /**
     * Visits one entry.
     *
     * @param keyBytes a borrowed view valid only during the call
     * @param value the decoded value; a flyweight value is reused across entries and valid only
     *     during the call
     * @return {@code false} to stop iteration
     */
    boolean visit(MemorySegment keyBytes, V value);
}
