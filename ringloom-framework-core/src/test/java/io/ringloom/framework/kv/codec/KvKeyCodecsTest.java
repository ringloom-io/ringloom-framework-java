// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

final class KvKeyCodecsTest {
    private static final long[] LONG_SAMPLES = {
        Long.MIN_VALUE,
        Long.MIN_VALUE + 1,
        -1_000_000_000_000L,
        -256,
        -255,
        -1,
        0,
        1,
        255,
        256,
        1_000_000_000_000L,
        Long.MAX_VALUE - 1,
        Long.MAX_VALUE
    };
    private static final int[] INT_SAMPLES = {
        Integer.MIN_VALUE,
        Integer.MIN_VALUE + 1,
        -65_536,
        -256,
        -1,
        0,
        1,
        255,
        65_536,
        Integer.MAX_VALUE - 1,
        Integer.MAX_VALUE
    };

    @Test
    void longRoundTripsAndPreservesSignedOrder() {
        MemorySegment scratch = MemorySegment.ofArray(new byte[8]);
        for (long value : LONG_SAMPLES) {
            assertThat(KvKeyCodecs.encodeLong(value, scratch, 8)).isEqualTo(8);
            assertThat(KvKeyCodecs.decodeLong(scratch)).isEqualTo(value);
            assertThat(KvKeyCodecs.LONG.decode(scratch, 0, 8)).isEqualTo(value);
        }
        SplittableRandom random = new SplittableRandom(16);
        for (int i = 0; i < 10_000; i++) {
            long a = i < LONG_SAMPLES.length ? LONG_SAMPLES[i] : random.nextLong();
            long b = random.nextLong();
            assertThat(Integer.signum(Arrays.compareUnsigned(longBytes(a), longBytes(b))))
                    .as("%d vs %d", a, b)
                    .isEqualTo(Integer.signum(Long.compare(a, b)));
        }
        for (int i = 1; i < LONG_SAMPLES.length; i++) {
            assertThat(Arrays.compareUnsigned(longBytes(LONG_SAMPLES[i - 1]), longBytes(LONG_SAMPLES[i])))
                    .isNegative();
        }
    }

    @Test
    void intRoundTripsAndPreservesSignedOrder() {
        MemorySegment scratch = MemorySegment.ofArray(new byte[4]);
        for (int value : INT_SAMPLES) {
            assertThat(KvKeyCodecs.encodeInt(value, scratch, 4)).isEqualTo(4);
            assertThat(KvKeyCodecs.decodeInt(scratch)).isEqualTo(value);
            assertThat(KvKeyCodecs.INT.decode(scratch, 0, 4)).isEqualTo(value);
        }
        SplittableRandom random = new SplittableRandom(17);
        for (int i = 0; i < 10_000; i++) {
            int a = random.nextInt();
            int b = random.nextInt();
            assertThat(Integer.signum(Arrays.compareUnsigned(intBytes(a), intBytes(b))))
                    .isEqualTo(Integer.signum(Integer.compare(a, b)));
        }
        for (int i = 1; i < INT_SAMPLES.length; i++) {
            assertThat(Arrays.compareUnsigned(intBytes(INT_SAMPLES[i - 1]), intBytes(INT_SAMPLES[i])))
                    .isNegative();
        }
    }

    @Test
    void numericKeysRejectTooSmallCapacity() {
        MemorySegment scratch = MemorySegment.ofArray(new byte[16]);
        MemorySegment small = MemorySegment.ofArray(new byte[3]);

        assertThat(KvKeyCodecs.encodeLong(1L, scratch, 7)).isEqualTo(KvKeyCodecs.DOES_NOT_FIT);
        assertThat(KvKeyCodecs.encodeInt(1, scratch, 3)).isEqualTo(KvKeyCodecs.DOES_NOT_FIT);
        assertThat(KvKeyCodecs.encodeInt(1, small, 100)).isEqualTo(KvKeyCodecs.DOES_NOT_FIT);
        assertThatThrownBy(() -> KvKeyCodecs.LONG.decode(scratch, 0, 4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodesAtOffset() {
        MemorySegment scratch = MemorySegment.ofArray(new byte[16]);
        KvKeyCodecs.encodeLong(-42L, scratch.asSlice(3), 8);
        KvKeyCodecs.encodeInt(-7, scratch.asSlice(11), 4);

        assertThat(KvKeyCodecs.decodeLong(scratch, 3)).isEqualTo(-42L);
        assertThat(KvKeyCodecs.decodeInt(scratch, 11)).isEqualTo(-7);
    }

    @Test
    void longGoldenVectors() {
        assertThat(hex(longBytes(0L))).isEqualTo("8000000000000000");
        assertThat(hex(longBytes(1L))).isEqualTo("8000000000000001");
        assertThat(hex(longBytes(-1L))).isEqualTo("7fffffffffffffff");
        assertThat(hex(longBytes(Long.MIN_VALUE))).isEqualTo("0000000000000000");
        assertThat(hex(longBytes(Long.MAX_VALUE))).isEqualTo("ffffffffffffffff");
        assertThat(hex(longBytes(0x0102030405060708L))).isEqualTo("8102030405060708");
        assertThat(hex(longBytes(-0x0102030405060708L))).isEqualTo("7efdfcfbfaf9f8f8");
    }

    @Test
    void intGoldenVectors() {
        assertThat(hex(intBytes(0))).isEqualTo("80000000");
        assertThat(hex(intBytes(1))).isEqualTo("80000001");
        assertThat(hex(intBytes(-1))).isEqualTo("7fffffff");
        assertThat(hex(intBytes(Integer.MIN_VALUE))).isEqualTo("00000000");
        assertThat(hex(intBytes(Integer.MAX_VALUE))).isEqualTo("ffffffff");
        assertThat(hex(intBytes(0x01020304))).isEqualTo("81020304");
    }

    @Test
    void stringGoldenVectors() {
        assertThat(hex(utf8Bytes(""))).isEmpty();
        assertThat(hex(utf8Bytes("AAPL"))).isEqualTo("4141504c");
        assertThat(hex(utf8Bytes("é"))).isEqualTo("c3a9");
        assertThat(hex(utf8Bytes("߿"))).isEqualTo("dfbf");
        assertThat(hex(utf8Bytes("ࠀ"))).isEqualTo("e0a080");
        assertThat(hex(utf8Bytes("€"))).isEqualTo("e282ac");
        assertThat(hex(utf8Bytes("￿"))).isEqualTo("efbfbf");
        assertThat(hex(utf8Bytes("😀"))).isEqualTo("f09f9880");
        assertThat(hex(utf8Bytes("􏿿"))).isEqualTo("f48fbfbf");
        assertThat(hex(utf8Bytes("a€😀"))).isEqualTo("61e282acf09f9880");
    }

    @Test
    void rawBytesGoldenVector() {
        MemorySegment scratch = MemorySegment.ofArray(new byte[8]);
        byte[] key = {0x00, (byte) 0xff, 0x10};

        assertThat(KvKeyCodecs.encodeBytes(key, scratch, 8)).isEqualTo(3);
        assertThat(hex(scratch.asSlice(0, 3).toArray(ValueLayout.JAVA_BYTE))).isEqualTo("00ff10");
        assertThat(KvKeyCodecs.decodeBytes(scratch, 0, 3)).containsExactly(key);
        assertThat(KvKeyCodecs.BYTES.decode(scratch, 1, 2)).containsExactly((byte) 0xff, (byte) 0x10);
        assertThat(KvKeyCodecs.encodeBytes(key, scratch, 2)).isEqualTo(KvKeyCodecs.DOES_NOT_FIT);
    }

    @Test
    void stringRoundTripsAsciiBmpAndSupplementary() {
        String[] samples = {"", "AAPL", "orders/2026-11-08", "café", "日本語", "€100", "😀", "x😀y𐐷", "\u0000nul"};
        MemorySegment scratch = MemorySegment.ofArray(new byte[128]);
        for (String sample : samples) {
            int written = KvKeyCodecs.encodeUtf8(sample, scratch, 128);
            byte[] expected = sample.getBytes(StandardCharsets.UTF_8);

            assertThat(written).as(sample).isEqualTo(expected.length);
            assertThat(KvKeyCodecs.utf8Length(sample)).isEqualTo(expected.length);
            assertThat(scratch.asSlice(0, written).toArray(ValueLayout.JAVA_BYTE))
                    .isEqualTo(expected);
            assertThat(KvKeyCodecs.decodeUtf8(scratch, 0, written)).isEqualTo(sample);
            assertThat(KvKeyCodecs.STRING.decode(scratch, 0, written)).isEqualTo(sample);
            assertThat(KvKeyCodecs.decodeUtf8(scratch.asSlice(0, written))).isEqualTo(sample);
        }
    }

    @Test
    void stringRejectsUnpairedSurrogates() {
        MemorySegment scratch = MemorySegment.ofArray(new byte[64]);
        String[] invalid = {"\ud83d", "a\ud83d", "\ud83db", "\ude00", "a\ude00\ud83d", "\ud83d\ud83d"};
        for (String sample : invalid) {
            assertThat(KvKeyCodecs.encodeUtf8(sample, scratch, 64))
                    .as(sample)
                    .isEqualTo(KvKeyCodecs.UNPAIRED_SURROGATE);
            assertThat(KvKeyCodecs.utf8Length(sample)).isEqualTo(KvKeyCodecs.UNPAIRED_SURROGATE);
            assertThat(KvKeyCodecs.STRING.encode(sample, scratch, 64)).isEqualTo(KvKeyCodecs.UNPAIRED_SURROGATE);
        }
        assertThat(KvKeyCodecs.UNPAIRED_SURROGATE).isNegative().isNotEqualTo(KvKeyCodecs.DOES_NOT_FIT);
    }

    @Test
    void stringRejectsTooSmallCapacityAtEveryBoundary() {
        String sample = "aé€😀";
        int length = sample.getBytes(StandardCharsets.UTF_8).length;
        MemorySegment scratch = MemorySegment.ofArray(new byte[64]);
        for (int capacity = 0; capacity < length; capacity++) {
            assertThat(KvKeyCodecs.encodeUtf8(sample, scratch, capacity))
                    .as("capacity %d", capacity)
                    .isEqualTo(KvKeyCodecs.DOES_NOT_FIT);
        }
        assertThat(KvKeyCodecs.encodeUtf8(sample, scratch, length)).isEqualTo(length);
        assertThat(KvKeyCodecs.encodeUtf8(sample, scratch.asSlice(0, 4), 64)).isEqualTo(KvKeyCodecs.DOES_NOT_FIT);
        assertThat(KvKeyCodecs.encodeUtf8("", scratch, 0)).isZero();
    }

    @Test
    void unpairedSurrogateTakesPrecedenceOverOverflow() {
        MemorySegment scratch = MemorySegment.ofArray(new byte[4]);

        assertThat(KvKeyCodecs.encodeUtf8("abcdefgh\ud83d", scratch, 4)).isEqualTo(KvKeyCodecs.UNPAIRED_SURROGATE);
        assertThat(KvKeyCodecs.encodeUtf8("abcdefgh😀", scratch, 4)).isEqualTo(KvKeyCodecs.DOES_NOT_FIT);
    }

    @Test
    void worksWithNativeTargets() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment scratch = arena.allocate(32);

            assertThat(KvKeyCodecs.encodeLong(-5L, scratch, 32)).isEqualTo(8);
            assertThat(KvKeyCodecs.decodeLong(scratch)).isEqualTo(-5L);
            assertThat(KvKeyCodecs.encodeUtf8("€", scratch, 32)).isEqualTo(3);
            assertThat(KvKeyCodecs.decodeUtf8(scratch, 0, 3)).isEqualTo("€");
        }
    }

    private static byte[] longBytes(long value) {
        MemorySegment scratch = MemorySegment.ofArray(new byte[8]);
        assertThat(KvKeyCodecs.encodeLong(value, scratch, 8)).isEqualTo(8);
        return scratch.toArray(ValueLayout.JAVA_BYTE);
    }

    private static byte[] intBytes(int value) {
        MemorySegment scratch = MemorySegment.ofArray(new byte[4]);
        assertThat(KvKeyCodecs.encodeInt(value, scratch, 4)).isEqualTo(4);
        return scratch.toArray(ValueLayout.JAVA_BYTE);
    }

    private static byte[] utf8Bytes(String value) {
        MemorySegment scratch = MemorySegment.ofArray(new byte[64]);
        int written = KvKeyCodecs.encodeUtf8(value, scratch, 64);
        assertThat(written).isNotNegative();
        return scratch.asSlice(0, written).toArray(ValueLayout.JAVA_BYTE);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
