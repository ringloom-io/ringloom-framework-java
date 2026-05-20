// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ringloom.framework.config.PartitionedExecutionConfig;
import io.ringloom.framework.config.WorkerBackpressurePolicy;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.framework.request.RequestResponseRegistry;
import io.ringloom.service.RingloomMessage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.junit.jupiter.api.Test;

final class MessageDispatchTest {
    @Test
    void messageContextCopiesMetadataFromRingloomMessage() {
        // Given
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment payload = arena.allocate(16);
            RingloomMessage message = message(payload);
            MessageContext context = new MessageContext();

            // When
            context.updateFrom(message);

            // Then
            assertThat(context.correlationId()).isEqualTo(99L);
            assertThat(context.sourceNodeId()).isEqualTo((short) 1);
            assertThat(context.sourceServiceId()).isEqualTo((short) 2);
            assertThat(context.targetNodeId()).isEqualTo((short) 3);
            assertThat(context.targetServiceId()).isEqualTo((short) 4);
            assertThat(context.templateId()).isEqualTo(77);
            assertThat(context.flags()).isEqualTo(5);
            assertThat(context.payloadSegment()).isSameAs(payload);
        }
    }

    @Test
    void messageContextUpdatesCopiedMetadata() {
        // Given
        MessageContext context = new MessageContext();

        // When
        context.updateCopied(11L, (short) 12, (short) 13, (short) 14, (short) 15, 16, 17, MemorySegment.NULL);

        // Then
        assertThat(context.correlationId()).isEqualTo(11L);
        assertThat(context.sourceNodeId()).isEqualTo((short) 12);
        assertThat(context.sourceServiceId()).isEqualTo((short) 13);
        assertThat(context.targetNodeId()).isEqualTo((short) 14);
        assertThat(context.targetServiceId()).isEqualTo((short) 15);
        assertThat(context.templateId()).isEqualTo(16);
        assertThat(context.flags()).isEqualTo(17);
        assertThat(context.payloadSegment()).isSameAs(MemorySegment.NULL);
    }

    @Test
    void consumerThreadPolicyUpdatesContextAndDelegatesToDispatcher() {
        // Given
        RingloomMessage message = message(MemorySegment.NULL);
        RequestResponseRegistry registry = mock(RequestResponseRegistry.class);
        GeneratedMessageDispatcher dispatcher = mock(GeneratedMessageDispatcher.class);
        MessageContext context = new MessageContext();
        when(dispatcher.onMessage(message, context)).thenReturn(123);
        ConsumerThreadExecutionPolicy policy = new ConsumerThreadExecutionPolicy(dispatcher, registry);

        // When
        int status = policy.onMessage(message, context);

        // Then
        assertThat(status).isEqualTo(123);
        assertThat(context.templateId()).isEqualTo(77);
        verify(dispatcher).onMessage(message, context);
    }

    @Test
    void partitionedWorkerPolicyDispatchesCopiedNativePayload() throws Exception {
        // Given
        CountDownLatch dispatched = new CountDownLatch(1);
        AtomicReference<MessageContext> observed = new AtomicReference<>();
        GeneratedMessageDispatcher dispatcher = new GeneratedMessageDispatcher() {
            @Override
            public int onMessage(RingloomMessage message, MessageContext context) {
                observed.set(context);
                dispatched.countDown();
                return 0;
            }
        };
        try (Arena arena = Arena.ofConfined();
                PartitionedWorkerExecutionPolicy policy = new PartitionedWorkerExecutionPolicy(
                        dispatcher,
                        (message, context) -> 7,
                        new PartitionedExecutionConfig(1, 2, 8, WorkerBackpressurePolicy.PARK_CONSUMER),
                        Thread.ofPlatform().name("partition-test-", 0).factory(),
                        NoOpIdleStrategy.INSTANCE)) {
            MemorySegment payload = arena.allocate(8);
            payload.set(ValueLayout.JAVA_LONG, 0, 42L);
            RingloomMessage message = message(payload);

            // When
            int status = policy.onMessage(message, new MessageContext());

            // Then
            assertThat(status).isEqualTo(1);
            assertThat(dispatched.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(observed.get().payloadSegment().isNative()).isTrue();
            assertThat(observed.get().payloadSegment().byteSize()).isEqualTo(8);
            assertThat(observed.get().payloadSegment().get(ValueLayout.JAVA_LONG, 0))
                    .isEqualTo(42L);
            assertThat(observed.get().correlationId()).isEqualTo(99L);
            assertThat(observed.get().sourceNodeId()).isEqualTo((short) 1);
            assertThat(observed.get().sourceServiceId()).isEqualTo((short) 2);
            assertThat(observed.get().targetNodeId()).isEqualTo((short) 3);
            assertThat(observed.get().targetServiceId()).isEqualTo((short) 4);
            assertThat(observed.get().templateId()).isEqualTo(77);
            assertThat(observed.get().flags()).isEqualTo(5);
        }
    }

    @Test
    void partitionedWorkerPolicyFailsFastWhenRingBufferIsFull() throws Exception {
        // Given
        CountDownLatch firstDispatchStarted = new CountDownLatch(1);
        CountDownLatch releaseDispatcher = new CountDownLatch(1);
        GeneratedMessageDispatcher dispatcher = new GeneratedMessageDispatcher() {
            @Override
            public int onMessage(RingloomMessage message, MessageContext context) {
                firstDispatchStarted.countDown();
                try {
                    releaseDispatcher.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return 0;
            }
        };
        try (Arena arena = Arena.ofConfined();
                PartitionedWorkerExecutionPolicy policy = new PartitionedWorkerExecutionPolicy(
                        dispatcher,
                        (message, context) -> 0,
                        new PartitionedExecutionConfig(1, 2, 64, WorkerBackpressurePolicy.FAIL_FAST),
                        Thread.ofPlatform().name("partition-full-test-", 0).factory(),
                        NoOpIdleStrategy.INSTANCE)) {
            RingloomMessage message = message(arena.allocate(64));
            assertThat(policy.onMessage(message, new MessageContext())).isEqualTo(1);
            assertThat(firstDispatchStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // When
            int status = 1;
            for (int i = 0; i < 10_000 && status == 1; i++) {
                status = policy.onMessage(message, new MessageContext());
            }

            // Then
            assertThat(status).isEqualTo(-1);
            releaseDispatcher.countDown();
        }
    }

    @Test
    void partitionedWorkerPolicyRejectsOversizedPayloads() {
        // Given
        GeneratedMessageDispatcher dispatcher = (message, context) -> 0;
        try (Arena arena = Arena.ofConfined();
                PartitionedWorkerExecutionPolicy policy = new PartitionedWorkerExecutionPolicy(
                        dispatcher,
                        (message, context) -> 0,
                        new PartitionedExecutionConfig(1, 2, 8, WorkerBackpressurePolicy.FAIL_FAST),
                        Thread.ofPlatform().name("partition-oversized-test-", 0).factory(),
                        NoOpIdleStrategy.INSTANCE)) {
            RingloomMessage message = message(arena.allocate(16));

            // When/Then
            assertThatThrownBy(() -> policy.onMessage(message, new MessageContext()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("payload exceeds partitioned worker maxPayloadBytes");
        }
    }

    @Test
    void partitionedWorkerPolicyPreservesPartitionAffinity() throws Exception {
        // Given
        AtomicReference<String> firstThread = new AtomicReference<>();
        AtomicReference<String> secondThread = new AtomicReference<>();
        CountDownLatch dispatched = new CountDownLatch(2);
        GeneratedMessageDispatcher dispatcher = new GeneratedMessageDispatcher() {
            @Override
            public int onMessage(RingloomMessage message, MessageContext context) {
                if (context.correlationId() == 1) {
                    firstThread.compareAndSet(null, Thread.currentThread().getName());
                } else if (context.correlationId() == 3) {
                    secondThread.compareAndSet(null, Thread.currentThread().getName());
                }
                dispatched.countDown();
                return 0;
            }
        };
        try (Arena arena = Arena.ofConfined();
                PartitionedWorkerExecutionPolicy policy = new PartitionedWorkerExecutionPolicy(
                        dispatcher,
                        (message, context) -> context.correlationId(),
                        new PartitionedExecutionConfig(2, 2, 8, WorkerBackpressurePolicy.PARK_CONSUMER),
                        Thread.ofPlatform().name("partition-affinity-test-", 0).factory(),
                        NoOpIdleStrategy.INSTANCE)) {
            RingloomMessage first = message(1L, arena.allocate(8));
            RingloomMessage second = message(3L, arena.allocate(8));

            // When
            policy.onMessage(first, new MessageContext());
            policy.onMessage(second, new MessageContext());

            // Then
            assertThat(dispatched.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(firstThread.get()).isEqualTo(secondThread.get());
        }
    }

    private static RingloomMessage message(MemorySegment payload) {
        return message(99L, payload);
    }

    private static RingloomMessage message(long correlationId, MemorySegment payload) {
        RingloomMessage message = mock(RingloomMessage.class);
        when(message.correlationId()).thenReturn(correlationId);
        when(message.sourceNodeId()).thenReturn((short) 1);
        when(message.sourceServiceId()).thenReturn((short) 2);
        when(message.targetNodeId()).thenReturn((short) 3);
        when(message.targetServiceId()).thenReturn((short) 4);
        when(message.templateId()).thenReturn(77);
        when(message.flags()).thenReturn(5);
        when(message.payloadSegment()).thenReturn(payload);
        when(message.payloadLength()).thenReturn(payload.byteSize());
        return message;
    }
}
