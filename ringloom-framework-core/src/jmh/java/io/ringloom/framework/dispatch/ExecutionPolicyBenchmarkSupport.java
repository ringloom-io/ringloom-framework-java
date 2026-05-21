// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import io.ringloom.service.RingloomMessage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class ExecutionPolicyBenchmarkSupport {
    static final int BATCH_SIZE = 16_384;
    static final int MESSAGE_SET_SIZE = 1024;

    private ExecutionPolicyBenchmarkSupport() {}

    static RingloomMessage[] messages(Arena arena, int payloadBytes) throws Exception {
        if (payloadBytes < Long.BYTES) {
            throw new IllegalArgumentException("payloadBytes must be at least " + Long.BYTES);
        }
        RingloomMessage[] messages = new RingloomMessage[MESSAGE_SET_SIZE];
        for (int i = 0; i < messages.length; i++) {
            MemorySegment payload = arena.allocate(payloadBytes);
            payload.set(ValueLayout.JAVA_LONG, 0, i + 1L);
            messages[i] = message(i + 1L, payload);
        }
        return messages;
    }

    static void waitFor(long target, AtomicLong processed, AtomicReference<Throwable> failure) {
        while (processed.get() < target) {
            Throwable error = failure.get();
            if (error != null) {
                throw new IllegalStateException("benchmark dispatcher failed", error);
            }
            java.util.concurrent.locks.LockSupport.parkNanos(1_000L);
        }
        Throwable error = failure.get();
        if (error != null) {
            throw new IllegalStateException("benchmark dispatcher failed", error);
        }
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
