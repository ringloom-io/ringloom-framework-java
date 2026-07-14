// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.config.PartitionedExecutionConfig;
import io.ringloom.framework.config.VirtualThreadExecutionConfig;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.framework.generated.GeneratedTopicDispatcher;
import io.ringloom.framework.request.RequestResponseRegistry;
import io.ringloom.framework.topic.TopicContext;
import io.ringloom.framework.topic.TopicMessage;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.junit.jupiter.api.Test;

final class TopicDispatchTest {

    private static final class RecordingTopicDispatcher implements GeneratedTopicDispatcher {
        final List<Long> seenIndexes = new CopyOnWriteArrayList<>();
        final List<byte[]> seenPayloads = new CopyOnWriteArrayList<>();

        @Override
        public int onTopicMessage(TopicMessage message, TopicContext context) {
            seenIndexes.add(message.index());
            seenPayloads.add(message.payloadSegment().toArray(ValueLayout.JAVA_BYTE));
            return io.ringloom.framework.status.RingloomHandlerStatus.OK;
        }

        @Override
        public int onContextTopicMessage(TopicContext context) {
            seenIndexes.add(context.index());
            seenPayloads.add(context.payloadSegment().toArray(ValueLayout.JAVA_BYTE));
            return io.ringloom.framework.status.RingloomHandlerStatus.OK;
        }
    }

    private static TopicMessage message(long topicId, long index, byte[] payload) {
        TopicMessage message = new TopicMessage();
        MemorySegment segment = payload.length == 0 ? MemorySegment.NULL : MemorySegment.ofArray(payload);
        message.updateFrom(topicId, segment, index);
        return message;
    }

    private static TopicContext context(RingloomRuntime runtime, String topicName) {
        TopicContext context = new TopicContext(runtime);
        context.updateCopied(0L, topicName, 0L, MemorySegment.NULL);
        return context;
    }

    @Test
    void consumerThreadDispatchesInline() {
        RecordingTopicDispatcher dispatcher = new RecordingTopicDispatcher();
        GeneratedMessageDispatcher unused = (message, context) -> io.ringloom.framework.status.RingloomHandlerStatus.OK;
        ConsumerThreadExecutionPolicy policy =
                new ConsumerThreadExecutionPolicy(unused, mock(RequestResponseRegistry.class));
        policy.topicDispatcher(dispatcher);

        TopicMessage message = message(42L, 7L, new byte[] {1, 2, 3});
        TopicContext context = context(null, "prices");
        // The source normally calls updateFrom on the context; simulate it.
        context.updateCopied(42L, "prices", 7L, message.payloadSegment());

        int status = policy.onTopicMessage(message, context);

        assertThat(status).isEqualTo(io.ringloom.framework.status.RingloomHandlerStatus.OK);
        assertThat(dispatcher.seenIndexes).containsExactly(7L);
        assertThat(dispatcher.seenPayloads.get(0)).containsExactly(1, 2, 3);
        policy.close();
    }

    @Test
    void consumerThreadWithoutDispatcherReturnsUnknownTopic() {
        GeneratedMessageDispatcher unused = (message, context) -> io.ringloom.framework.status.RingloomHandlerStatus.OK;
        ConsumerThreadExecutionPolicy policy =
                new ConsumerThreadExecutionPolicy(unused, mock(RequestResponseRegistry.class));

        int status = policy.onTopicMessage(message(1L, 1L, new byte[] {9}), context(null, "x"));

        assertThat(status).isEqualTo(io.ringloom.framework.status.RingloomHandlerStatus.UNKNOWN_TOPIC);
        policy.close();
    }

    @Test
    void virtualThreadDispatchesCopied() throws Exception {
        RecordingTopicDispatcher dispatcher = new RecordingTopicDispatcher();
        GeneratedMessageDispatcher unused = (message, context) -> io.ringloom.framework.status.RingloomHandlerStatus.OK;
        VirtualThreadExecutionPolicy policy =
                new VirtualThreadExecutionPolicy(unused, null, new VirtualThreadExecutionConfig(8));
        policy.topicDispatcher(dispatcher);

        TopicMessage message = message(5L, 3L, new byte[] {4, 5});
        TopicContext context = context(null, "trades");
        context.updateCopied(5L, "trades", 3L, message.payloadSegment());

        int status = policy.onTopicMessage(message, context);
        assertThat(status).isEqualTo(1);

        // Virtual threads are async; wait for the dispatch.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (dispatcher.seenIndexes.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        policy.close();

        assertThat(dispatcher.seenIndexes).containsExactly(3L);
        assertThat(dispatcher.seenPayloads.get(0)).containsExactly(4, 5);
    }

    @Test
    void partitionedWorkerDispatchesTopicByTopicId() throws Exception {
        RecordingTopicDispatcher dispatcher = new RecordingTopicDispatcher();
        GeneratedMessageDispatcher unused = (message, context) -> io.ringloom.framework.status.RingloomHandlerStatus.OK;
        PartitionedExecutionConfig config = PartitionedExecutionConfig.defaults();
        PartitionedWorkerExecutionPolicy policy = new PartitionedWorkerExecutionPolicy(
                null,
                unused,
                (message, ctx) -> 0L,
                dispatcher,
                null, // keyless: route by topic id
                config,
                Thread.ofPlatform().name("test-worker-").factory(),
                NoOpIdleStrategy.INSTANCE);

        // Send two messages for the same topic id → same worker, order preserved.
        TopicMessage m1 = message(99L, 1L, new byte[] {10});
        TopicContext c1 = context(null, "ticks");
        c1.updateCopied(99L, "ticks", 1L, m1.payloadSegment());
        TopicMessage m2 = message(99L, 2L, new byte[] {20});
        TopicContext c2 = context(null, "ticks");
        c2.updateCopied(99L, "ticks", 2L, m2.payloadSegment());

        assertThat(policy.onTopicMessage(m1, c1)).isEqualTo(1);
        assertThat(policy.onTopicMessage(m2, c2)).isEqualTo(1);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (dispatcher.seenIndexes.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        policy.close();

        assertThat(dispatcher.seenIndexes).containsExactly(1L, 2L);
        assertThat(dispatcher.seenPayloads.get(0)).containsExactly(10);
        assertThat(dispatcher.seenPayloads.get(1)).containsExactly(20);
    }
}
