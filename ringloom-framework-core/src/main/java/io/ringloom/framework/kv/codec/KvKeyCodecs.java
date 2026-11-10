// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv.codec;

import io.ringloom.framework.serialization.MessageDecoder;
import io.ringloom.framework.serialization.MessageEncoder;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Built-in KV key encodings and allocation-free helpers.
 *
 * <p>These encodings are a <strong>cross-language contract</strong>; other bindings (C++, Node)
 * must produce the same bytes:
 *
 * <ul>
 *   <li>{@code long}: 8 bytes, big-endian, sign bit flipped ({@code key ^ Long.MIN_VALUE}), so
 *       unsigned byte-wise order equals signed numeric order.
 *   <li>{@code int}: 4 bytes, big-endian, sign bit flipped ({@code key ^ Integer.MIN_VALUE}).
 *   <li>{@code String}: standard UTF-8 (4-byte sequences for supplementary code points); unpaired
 *       surrogates are rejected.
 *   <li>{@code byte[]} and {@code MemorySegment}: the raw bytes, unchanged.
 * </ul>
 *
 * <p>Iteration order is engine-defined even though these encodings sort numerically on LMDB.
 *
 * <p>Encode methods return the number of bytes written, or a negative sentinel:
 * {@link #DOES_NOT_FIT} ({@code -1}) when the key is longer than the capacity, and
 * {@link #UNPAIRED_SURROGATE} ({@code -2}) when a {@code String} key contains an unpaired surrogate
 * (callers map this to {@code RingloomStatus.INVALID_ARGUMENT}).
 */
public final class KvKeyCodecs {
    /** The key does not fit in the supplied capacity. */
    public static final int DOES_NOT_FIT = KvKeyCodec.DOES_NOT_FIT;

    /**
     * A {@code String} key contains an unpaired surrogate and has no UTF-8 encoding. Takes precedence
     * over {@link #DOES_NOT_FIT}: the whole string is validated even when it overflows.
     */
    public static final int UNPAIRED_SURROGATE = -2;

    /** Encoded size of a {@code long} key. */
    public static final int LONG_KEY_BYTES = Long.BYTES;

    /** Encoded size of an {@code int} key. */
    public static final int INT_KEY_BYTES = Integer.BYTES;

    private static final ValueLayout.OfLong LONG_BE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt INT_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    /** Boxing codec for {@code long}/{@link Long} keys. Generated code inlines the static helpers instead. */
    public static final KvKeyCodec<Long> LONG = new KvKeyCodec<>() {
        @Override
        public int encode(Long key, MemorySegment target, long capacity) {
            return encodeLong(key, target, capacity);
        }

        @Override
        public Long decode(MemorySegment source, long offset, long length) {
            checkLength(length, LONG_KEY_BYTES);
            return decodeLong(source, offset);
        }
    };

    /** Boxing codec for {@code int}/{@link Integer} keys. Generated code inlines the static helpers instead. */
    public static final KvKeyCodec<Integer> INT = new KvKeyCodec<>() {
        @Override
        public int encode(Integer key, MemorySegment target, long capacity) {
            return encodeInt(key, target, capacity);
        }

        @Override
        public Integer decode(MemorySegment source, long offset, long length) {
            checkLength(length, INT_KEY_BYTES);
            return decodeInt(source, offset);
        }
    };

    /** UTF-8 codec for {@link String} keys. */
    public static final KvKeyCodec<String> STRING = new KvKeyCodec<>() {
        @Override
        public int encode(String key, MemorySegment target, long capacity) {
            return encodeUtf8(key, target, capacity);
        }

        @Override
        public String decode(MemorySegment source, long offset, long length) {
            return decodeUtf8(source, offset, length);
        }
    };

    /** Raw codec for {@code byte[]} keys (copied into the target). */
    public static final KvKeyCodec<byte[]> BYTES = new KvKeyCodec<>() {
        @Override
        public int encode(byte[] key, MemorySegment target, long capacity) {
            return encodeBytes(key, target, capacity);
        }

        @Override
        public byte[] decode(MemorySegment source, long offset, long length) {
            return decodeBytes(source, offset, length);
        }
    };

    private KvKeyCodecs() {}

    // ---- long ----------------------------------------------------------------------------------

    /**
     * Writes {@code key} as 8 big-endian bytes with the sign bit flipped at offset 0.
     *
     * @return {@link #LONG_KEY_BYTES}, or {@link #DOES_NOT_FIT}
     */
    public static int encodeLong(long key, MemorySegment target, long capacity) {
        if (Math.min(capacity, target.byteSize()) < LONG_KEY_BYTES) {
            return DOES_NOT_FIT;
        }
        target.set(LONG_BE, 0L, key ^ Long.MIN_VALUE);
        return LONG_KEY_BYTES;
    }

    /** Decodes a {@code long} key from offset 0. */
    public static long decodeLong(MemorySegment source) {
        return decodeLong(source, 0L);
    }

    /** Decodes a {@code long} key from {@code offset}. */
    public static long decodeLong(MemorySegment source, long offset) {
        return source.get(LONG_BE, offset) ^ Long.MIN_VALUE;
    }

    // ---- int -----------------------------------------------------------------------------------

    /**
     * Writes {@code key} as 4 big-endian bytes with the sign bit flipped at offset 0.
     *
     * @return {@link #INT_KEY_BYTES}, or {@link #DOES_NOT_FIT}
     */
    public static int encodeInt(int key, MemorySegment target, long capacity) {
        if (Math.min(capacity, target.byteSize()) < INT_KEY_BYTES) {
            return DOES_NOT_FIT;
        }
        target.set(INT_BE, 0L, key ^ Integer.MIN_VALUE);
        return INT_KEY_BYTES;
    }

    /** Decodes an {@code int} key from offset 0. */
    public static int decodeInt(MemorySegment source) {
        return decodeInt(source, 0L);
    }

    /** Decodes an {@code int} key from {@code offset}. */
    public static int decodeInt(MemorySegment source, long offset) {
        return source.get(INT_BE, offset) ^ Integer.MIN_VALUE;
    }

    // ---- String --------------------------------------------------------------------------------

    /**
     * Encodes {@code key} as UTF-8 at offset 0 without allocating.
     *
     * @param key the key
     * @param target the destination
     * @param capacity maximum bytes to write; clamped to {@code target.byteSize()}
     * @return bytes written; {@link #UNPAIRED_SURROGATE} if the string contains an unpaired
     *     surrogate (checked over the whole string, even past an overflow); otherwise
     *     {@link #DOES_NOT_FIT} if the encoding is longer than the capacity. On a negative result the
     *     target may contain partially written bytes.
     */
    public static int encodeUtf8(String key, MemorySegment target, long capacity) {
        long limit = Math.min(capacity, target.byteSize());
        int length = key.length();
        long pos = 0;
        int i = 0;
        while (i < length) {
            char c = key.charAt(i);
            if (c < 0x80) {
                if (pos + 1 > limit) {
                    return overflow(key, i);
                }
                target.set(ValueLayout.JAVA_BYTE, pos++, (byte) c);
                i++;
            } else if (c < 0x800) {
                if (pos + 2 > limit) {
                    return overflow(key, i);
                }
                target.set(ValueLayout.JAVA_BYTE, pos++, (byte) (0xC0 | (c >> 6)));
                target.set(ValueLayout.JAVA_BYTE, pos++, (byte) (0x80 | (c & 0x3F)));
                i++;
            } else if (Character.isSurrogate(c)) {
                if (!Character.isHighSurrogate(c) || i + 1 >= length || !Character.isLowSurrogate(key.charAt(i + 1))) {
                    return UNPAIRED_SURROGATE;
                }
                if (pos + 4 > limit) {
                    return overflow(key, i);
                }
                int cp = Character.toCodePoint(c, key.charAt(i + 1));
                target.set(ValueLayout.JAVA_BYTE, pos++, (byte) (0xF0 | (cp >> 18)));
                target.set(ValueLayout.JAVA_BYTE, pos++, (byte) (0x80 | ((cp >> 12) & 0x3F)));
                target.set(ValueLayout.JAVA_BYTE, pos++, (byte) (0x80 | ((cp >> 6) & 0x3F)));
                target.set(ValueLayout.JAVA_BYTE, pos++, (byte) (0x80 | (cp & 0x3F)));
                i += 2;
            } else {
                if (pos + 3 > limit) {
                    return overflow(key, i);
                }
                target.set(ValueLayout.JAVA_BYTE, pos++, (byte) (0xE0 | (c >> 12)));
                target.set(ValueLayout.JAVA_BYTE, pos++, (byte) (0x80 | ((c >> 6) & 0x3F)));
                target.set(ValueLayout.JAVA_BYTE, pos++, (byte) (0x80 | (c & 0x3F)));
                i++;
            }
        }
        return (int) pos;
    }

    /**
     * Returns the UTF-8 length of {@code key} without allocating, or {@link #UNPAIRED_SURROGATE}.
     */
    public static int utf8Length(String key) {
        int length = key.length();
        int bytes = 0;
        for (int i = 0; i < length; i++) {
            char c = key.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isSurrogate(c)) {
                if (!Character.isHighSurrogate(c) || i + 1 >= length || !Character.isLowSurrogate(key.charAt(i + 1))) {
                    return UNPAIRED_SURROGATE;
                }
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /** Decodes a UTF-8 key. Allocates the returned string. */
    public static String decodeUtf8(MemorySegment source, long offset, long length) {
        byte[] bytes = new byte[Math.toIntExact(length)];
        MemorySegment.copy(source, ValueLayout.JAVA_BYTE, offset, bytes, 0, bytes.length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Decodes a UTF-8 key occupying the whole segment. */
    public static String decodeUtf8(MemorySegment source) {
        return decodeUtf8(source, 0L, source.byteSize());
    }

    private static int overflow(String key, int from) {
        int length = key.length();
        for (int i = from; i < length; i++) {
            char c = key.charAt(i);
            if (Character.isSurrogate(c)) {
                if (!Character.isHighSurrogate(c) || i + 1 >= length || !Character.isLowSurrogate(key.charAt(i + 1))) {
                    return UNPAIRED_SURROGATE;
                }
                i++;
            }
        }
        return DOES_NOT_FIT;
    }

    // ---- raw bytes -----------------------------------------------------------------------------

    /**
     * Copies {@code key} into {@code target} at offset 0.
     *
     * @return {@code key.length}, or {@link #DOES_NOT_FIT}
     */
    public static int encodeBytes(byte[] key, MemorySegment target, long capacity) {
        if (key.length > Math.min(capacity, target.byteSize())) {
            return DOES_NOT_FIT;
        }
        MemorySegment.copy(key, 0, target, ValueLayout.JAVA_BYTE, 0L, key.length);
        return key.length;
    }

    /** Copies key bytes out of {@code source}. Allocates. */
    public static byte[] decodeBytes(MemorySegment source, long offset, long length) {
        byte[] bytes = new byte[Math.toIntExact(length)];
        MemorySegment.copy(source, ValueLayout.JAVA_BYTE, offset, bytes, 0, bytes.length);
        return bytes;
    }

    // ---- serializer-backed ---------------------------------------------------------------------

    /**
     * Returns a key codec backed by a framework serializer.
     *
     * @param encoder the key encoder
     * @param decoder the key decoder, or {@code null} for an encode-only codec
     * @param <K> the key type
     * @return the codec
     */
    public static <K> KvKeyCodec<K> serializer(MessageEncoder<K> encoder, MessageDecoder<K> decoder) {
        return new SerializerKvKeyCodec<>(encoder, decoder);
    }

    private static void checkLength(long actual, int expected) {
        if (actual != expected) {
            throw new IllegalArgumentException("expected " + expected + " key bytes but got " + actual);
        }
    }
}
