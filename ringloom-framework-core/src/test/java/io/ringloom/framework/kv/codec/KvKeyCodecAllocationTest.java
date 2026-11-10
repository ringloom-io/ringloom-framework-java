// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/** Verifies that the built-in key encoders do not allocate once warmed up. */
final class KvKeyCodecAllocationTest {
    private static final int WARMUP_ITERATIONS = 200_000;
    private static final int MEASURED_ITERATIONS = 100_000;
    private static final int ATTEMPTS = 5;

    private static final String ASCII_KEY = "orders/AAPL/2026-11-08";
    private static final String MIXED_KEY = "café-€-😀";

    private long sink;

    @Test
    void builtInEncodersDoNotAllocate() {
        com.sun.management.ThreadMXBean threads = threadMxBean();
        long threadId = Thread.currentThread().threadId();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeScratch = arena.allocate(256);
            MemorySegment heapScratch = MemorySegment.ofArray(new byte[256]);
            byte[] rawKey = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

            run(nativeScratch, heapScratch, rawKey, WARMUP_ITERATIONS);

            long allocated = Long.MAX_VALUE;
            for (int attempt = 0; attempt < ATTEMPTS && allocated != 0; attempt++) {
                long before = threads.getThreadAllocatedBytes(threadId);
                run(nativeScratch, heapScratch, rawKey, MEASURED_ITERATIONS);
                long after = threads.getThreadAllocatedBytes(threadId);
                allocated = after - before - measurementOverhead(threads, threadId);
            }

            assertThat(allocated)
                    .as("bytes allocated by %d encodes", MEASURED_ITERATIONS)
                    .isLessThanOrEqualTo(0L);
            assertThat(sink).isNotZero();
        }
    }

    private void run(MemorySegment nativeScratch, MemorySegment heapScratch, byte[] rawKey, int iterations) {
        long acc = 0;
        for (int i = 0; i < iterations; i++) {
            acc += KvKeyCodecs.encodeLong(i - 50_000L, nativeScratch, 256);
            acc += KvKeyCodecs.encodeInt(i - 50_000, nativeScratch, 256);
            acc += KvKeyCodecs.encodeUtf8(ASCII_KEY, nativeScratch, 256);
            acc += KvKeyCodecs.encodeUtf8(MIXED_KEY, nativeScratch, 256);
            acc += KvKeyCodecs.encodeLong(i, heapScratch, 256);
            acc += KvKeyCodecs.encodeInt(i, heapScratch, 256);
            acc += KvKeyCodecs.encodeUtf8(MIXED_KEY, heapScratch, 256);
            acc += KvKeyCodecs.encodeBytes(rawKey, nativeScratch, 256);
            acc += KvKeyCodecs.decodeLong(nativeScratch);
            acc += KvKeyCodecs.decodeInt(heapScratch);
        }
        sink += acc;
    }

    private static long measurementOverhead(com.sun.management.ThreadMXBean threads, long threadId) {
        long first = threads.getThreadAllocatedBytes(threadId);
        long second = threads.getThreadAllocatedBytes(threadId);
        return second - first;
    }

    private static com.sun.management.ThreadMXBean threadMxBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean, "thread allocation counters unavailable");
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(threads.isThreadAllocatedMemorySupported(), "thread allocation counters unsupported");
        if (!threads.isThreadAllocatedMemoryEnabled()) {
            threads.setThreadAllocatedMemoryEnabled(true);
        }
        return threads;
    }
}
