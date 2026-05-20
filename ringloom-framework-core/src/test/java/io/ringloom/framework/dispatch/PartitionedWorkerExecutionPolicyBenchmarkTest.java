// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.ringloom.framework.config.PartitionedExecutionConfig;
import io.ringloom.framework.config.WorkerBackpressurePolicy;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.service.RingloomMessage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.junit.jupiter.api.Test;

final class PartitionedWorkerExecutionPolicyBenchmarkTest {
    private static final int MESSAGE_SET_SIZE = 1024;

    @Test
    void partitionedWorkerThroughputBenchmark() throws Exception {
        assumeTrue(
                Boolean.getBoolean("ringloom.benchmark") || Boolean.parseBoolean(System.getenv("RINGLOOM_BENCHMARK")));

        // Given
        int messages = Integer.getInteger("ringloom.benchmark.messages", 500_000);
        int workers = Integer.getInteger("ringloom.benchmark.workers", 4);
        int queueCapacity = Integer.getInteger("ringloom.benchmark.queueCapacity", 1024);
        int payloadBytes = Integer.getInteger("ringloom.benchmark.payloadBytes", 128);
        AtomicLong processed = new AtomicLong();
        AtomicLong checksum = new AtomicLong();
        GeneratedMessageDispatcher dispatcher = new GeneratedMessageDispatcher() {
            @Override
            public int onMessage(RingloomMessage message, MessageContext context) {
                checksum.addAndGet(context.payloadSegment().get(ValueLayout.JAVA_LONG, 0));
                processed.incrementAndGet();
                return 0;
            }
        };

        try (Arena arena = Arena.ofShared();
                PartitionedWorkerExecutionPolicy policy = new PartitionedWorkerExecutionPolicy(
                        dispatcher,
                        (message, context) -> context.correlationId(),
                        new PartitionedExecutionConfig(
                                workers, queueCapacity, payloadBytes, WorkerBackpressurePolicy.PARK_CONSUMER),
                        Thread.ofPlatform().name("partition-benchmark-", 0).factory(),
                        NoOpIdleStrategy.INSTANCE)) {
            RingloomMessage[] messageSet = messages(arena, payloadBytes);
            MessageContext context = new MessageContext();

            // When
            long started = System.nanoTime();
            for (int i = 0; i < messages; i++) {
                policy.onMessage(messageSet[i & (MESSAGE_SET_SIZE - 1)], context);
            }
            while (processed.get() < messages) {
                LockSupport.parkNanos(1_000L);
            }
            long elapsedNanos = System.nanoTime() - started;

            // Then
            double seconds = elapsedNanos / 1_000_000_000.0;
            double throughput = messages / seconds;
            System.out.printf(
                    "PartitionedWorker benchmark: messages=%d workers=%d queueCapacity=%d payloadBytes=%d "
                            + "elapsedMs=%.3f throughputMsgPerSec=%.0f checksum=%d%n",
                    messages,
                    workers,
                    queueCapacity,
                    payloadBytes,
                    elapsedNanos / 1_000_000.0,
                    throughput,
                    checksum.get());
        }
    }

    private static RingloomMessage[] messages(Arena arena, int payloadBytes) throws Exception {
        RingloomMessage[] messages = new RingloomMessage[MESSAGE_SET_SIZE];
        for (int i = 0; i < messages.length; i++) {
            MemorySegment payload = arena.allocate(payloadBytes);
            payload.set(ValueLayout.JAVA_LONG, 0, i + 1L);
            messages[i] = message(i + 1L, payload);
        }
        return messages;
    }

    private static RingloomMessage message(long correlationId, MemorySegment payload) throws Exception {
        Constructor<RingloomMessage> constructor = RingloomMessage.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RingloomMessage message = constructor.newInstance();
        set(message, "correlationId", correlationId);
        set(message, "sourceNodeId", (short) 1);
        set(message, "sourceServiceId", (short) 2);
        set(message, "targetNodeId", (short) 3);
        set(message, "targetServiceId", (short) 4);
        set(message, "templateId", (short) 77);
        set(message, "flags", (byte) 5);
        set(message, "payloadAddress", payload.address());
        set(message, "payloadLength", payload.byteSize());
        return message;
    }

    private static void set(RingloomMessage message, String fieldName, Object value) throws Exception {
        Field field = RingloomMessage.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(message, value);
    }
}
