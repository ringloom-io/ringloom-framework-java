// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.framework.request.RequestResponseRegistry;
import io.ringloom.service.RingloomMessage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
                        new io.ringloom.framework.config.PartitionedExecutionConfig(
                                1, 2, 8, io.ringloom.framework.config.WorkerBackpressurePolicy.PARK_CONSUMER),
                        Thread.ofPlatform().name("partition-test-", 0).factory(),
                        new io.ringloom.framework.eventloop.NoOpIdleStrategy())) {
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
        }
    }

    private static RingloomMessage message(MemorySegment payload) {
        RingloomMessage message = mock(RingloomMessage.class);
        when(message.correlationId()).thenReturn(99L);
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
