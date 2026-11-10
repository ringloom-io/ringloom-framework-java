// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv.codec;

import java.lang.foreign.MemorySegment;

/**
 * Encodes and decodes KV store keys.
 *
 * <p>Key bytes are a cross-language contract: every binding reading or writing the same store must
 * produce identical bytes for equal keys. See {@link KvKeyCodecs} for the built-in encodings.
 *
 * @param <K> the key type
 */
public interface KvKeyCodec<K> {
    /** Returned by {@link #encode} when the key does not fit in {@code capacity} bytes. */
    int DOES_NOT_FIT = -1;

    /**
     * Encodes {@code key} into {@code target} starting at offset 0.
     *
     * <p>Built-in codecs do not allocate.
     *
     * @param key the key to encode
     * @param target the destination segment
     * @param capacity the maximum number of bytes to write; clamped to {@code target.byteSize()}
     * @return bytes written, {@link #DOES_NOT_FIT} if the key does not fit, or another negative
     *     codec-specific sentinel (for example {@link KvKeyCodecs#UNPAIRED_SURROGATE})
     */
    int encode(K key, MemorySegment target, long capacity);

    /**
     * Decodes a key, typically from an iteration callback. May allocate (for example
     * {@code String}).
     *
     * @param source the segment holding the key bytes
     * @param offset the offset of the key in {@code source}
     * @param length the key length in bytes
     * @return the decoded key
     */
    K decode(MemorySegment source, long offset, long length);
}
