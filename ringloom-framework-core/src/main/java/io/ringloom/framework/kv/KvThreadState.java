// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import io.ringloom.framework.serialization.DecodeContext;
import io.ringloom.framework.serialization.EncodeContext;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Per-thread KV scratch state, one per thread per {@link KvRuntime} (held in a {@link ThreadLocal}).
 * Used by generated store proxies; not thread-safe.
 *
 * <p>Scratch is native memory on every thread (platform and virtual) because SBE buffers and the
 * binding's {@code put}/{@code delete} require native segments. Each buffer is allocated from its own
 * automatic arena, so the memory is reclaimed by the GC once the owning thread (or the runtime) is gone, and
 * a thread still using its scratch while the runtime closes never touches freed memory. Native
 * memory per thread is about {@link #KEY_SCRATCH_BYTES} plus the largest value length seen (see
 * {@code maxValueLength}).
 *
 * <p>The value scratch grows on demand (one allocation per growth); a flyweight returned from a
 * generated {@code get} points into it and is valid until the next KV read of any store on the same
 * thread.
 */
public final class KvThreadState {
    /** Key scratch capacity: the LMDB key limit (511 bytes, native §4.2) rounded up. */
    public static final int KEY_SCRATCH_BYTES = 512;

    private static final long ALIGNMENT = 8;

    private final MemorySegment keyScratch;
    private final long[] outLength = new long[1];
    private final EncodeContext encodeContext = new EncodeContext();
    private final DecodeContext decodeContext = new DecodeContext();
    private MemorySegment valueScratch;

    KvThreadState(long initialValueCapacity) {
        this.keyScratch = allocate(KEY_SCRATCH_BYTES);
        this.valueScratch = allocate(Math.max(ALIGNMENT, initialValueCapacity));
    }

    /**
     * Returns the key scratch ({@link #KEY_SCRATCH_BYTES} bytes, native).
     *
     * @return the key scratch
     */
    public MemorySegment keyScratch() {
        return keyScratch;
    }

    /**
     * Returns the current value scratch (native).
     *
     * @return the value scratch
     */
    public MemorySegment valueScratch() {
        return valueScratch;
    }

    /**
     * Returns a value scratch of at least {@code required} bytes, growing it when needed. Growing
     * invalidates flyweights pointing into the previous scratch.
     *
     * @param required the required capacity in bytes
     * @return the value scratch
     */
    public MemorySegment valueScratch(long required) {
        MemorySegment current = valueScratch;
        if (current.byteSize() >= required) {
            return current;
        }
        long capacity = Math.max(required, Math.min(current.byteSize() * 2, Integer.MAX_VALUE));
        MemorySegment grown = allocate(capacity);
        valueScratch = grown;
        return grown;
    }

    /** Each buffer gets its own automatic arena so a replaced (grown) buffer is reclaimed on its own. */
    private static MemorySegment allocate(long bytes) {
        return Arena.ofAuto().allocate(bytes, ALIGNMENT);
    }

    /**
     * Returns the reusable length holder passed to native gets.
     *
     * @return the length holder
     */
    public long[] outLength() {
        return outLength;
    }

    /**
     * Returns the reusable encode context.
     *
     * @return the encode context
     */
    public EncodeContext encodeContext() {
        return encodeContext;
    }

    /**
     * Returns the reusable decode context.
     *
     * @return the decode context
     */
    public DecodeContext decodeContext() {
        return decodeContext;
    }
}
