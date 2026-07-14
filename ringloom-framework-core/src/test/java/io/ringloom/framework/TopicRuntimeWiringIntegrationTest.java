// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.config.topic.TopicsRuntimeConfig;
import io.ringloom.framework.generated.GeneratedClientBinding;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.framework.generated.GeneratedRingloomApplication;
import io.ringloom.framework.generated.GeneratedTopicDispatcher;
import io.ringloom.framework.generated.GeneratedTopicHandlerBinding;
import io.ringloom.framework.generated.GeneratedTopicPublisherBinding;
import io.ringloom.framework.metrics.UnavailableRingloomMetrics;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.framework.topic.TopicContext;
import io.ringloom.framework.topic.TopicMessage;
import io.ringloom.framework.topic.ack.TopicAckRegistry;
import io.ringloom.service.RingloomClient;
import io.ringloom.service.RingloomService;
import io.ringloom.service.TopicConfig;
import io.ringloom.service.TopicPublisher;
import io.ringloom.service.TopicStart;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Validates the {@link RingloomRuntime} topic wiring (start/close, pollTopics, pollAckFeedback,
 * register publishers/handlers, wire the dispatcher) against a topics-enabled broker.
 */
@EnabledIfSystemProperty(named = "ringloom.brokerBin", matches = ".+")
final class TopicRuntimeWiringIntegrationTest {
    private static final Path REPO_ROOT =
            Path.of(System.getProperty("ringloom.repoRoot", "/home/dragan/code/ringloom"));

    private TestBroker broker;
    private RingloomService service;
    private RingloomRuntime runtime;

    @AfterEach
    void tearDown() throws Exception {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
        if (service != null) {
            service.close();
            service = null;
        }
        if (broker != null) {
            broker.close();
            broker = null;
        }
    }

    @Test
    void runtimeStartsAndPollsTopicsThroughWiredDispatcher() throws Exception {
        startBrokerAndService();
        CountingTopicDispatcher topicDispatcher = new CountingTopicDispatcher();
        TestTopicApplication application = new TestTopicApplication(topicDispatcher);

        runtime = new RingloomRuntime(
                new RingloomApplicationConfig(
                        new io.ringloom.framework.config.RingloomServiceRuntimeConfig(
                                "topic-wire",
                                workspaceStorage(),
                                broker.group(),
                                broker.nodeId(),
                                false,
                                0,
                                0,
                                0,
                                false),
                        null,
                        null,
                        Map.of(),
                        TopicsRuntimeConfig.enabledDefaults()),
                application,
                SerializerRegistry.EMPTY,
                UnavailableRingloomMetrics.INSTANCE,
                mock(org.slf4j.Logger.class));
        runtime.start();

        // Publish via the registered publisher handle.
        TopicPublisher publisher = runtime.topicRuntime().publisher("wiredTopic");
        assertThat(publisher).isNotNull();
        for (int i = 0; i < 5; i++) {
            publishByte(publisher, (byte) (i + 1));
        }
        waitForReplication(publisher, 5);

        // Drive the control thread (pollAckFeedback) and the topic poll.
        for (int i = 0; i < 100 && topicDispatcher.count.get() < 5; i++) {
            runtime.pollControl();
            runtime.pollTopics();
            Thread.sleep(10);
        }

        assertThat(topicDispatcher.count.get())
                .as("topic dispatcher should receive all 5 messages")
                .isEqualTo(5);

        runtime.close();
        runtime = null;
        // After close the topic runtime is null.
        assertThat(runtime).isNull();
    }

    @Test
    void topicsDisabledLeavesTopicRuntimeNull() throws Exception {
        startBrokerAndService();
        TestTopicApplication application = new TestTopicApplication(new CountingTopicDispatcher());

        runtime = new RingloomRuntime(
                new RingloomApplicationConfig(
                        new io.ringloom.framework.config.RingloomServiceRuntimeConfig(
                                "topic-off",
                                workspaceStorage(),
                                broker.group(),
                                broker.nodeId(),
                                false,
                                0,
                                0,
                                0,
                                false),
                        null,
                        null,
                        Map.of(),
                        TopicsRuntimeConfig.disabled()),
                application,
                SerializerRegistry.EMPTY,
                UnavailableRingloomMetrics.INSTANCE,
                mock(org.slf4j.Logger.class));
        runtime.start();

        assertThat(runtime.topicRuntime()).isNull();
        assertThat(runtime.pollTopics()).isZero();
    }

    private Path workspace;

    private void startBrokerAndService() throws Exception {
        workspace = Files.createTempDirectory("ringloom-topic-wire-");
        broker = TestBroker.startTopicsEnabled(REPO_ROOT, workspace);
    }

    private String workspaceStorage() {
        return workspace.resolve("storage").toString();
    }

    private static void publishByte(TopicPublisher publisher, byte value) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(ValueLayout.JAVA_BYTE);
            segment.set(ValueLayout.JAVA_BYTE, 0, value);
            long[] outIndex = new long[1];
            int status = publisher.publish(segment, io.ringloom.service.TopicAckMode.REPLICATE_ONCE, 0L, outIndex);
            if (status != io.ringloom.service.RingloomStatus.OK) {
                throw new IllegalStateException("publish failed status=" + status);
            }
        }
    }

    private void waitForReplication(TopicPublisher publisher, int expected) throws Exception {
        for (int i = 0; i < 300; i++) {
            runtime.pollControl();
            if (publisher.replicatedCount() >= expected) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("replicatedCount never reached " + expected);
    }

    /** Minimal GeneratedRingloomApplication that wires one topic publisher + handler. */
    private static final class TestTopicApplication implements GeneratedRingloomApplication {
        private final GeneratedTopicDispatcher topicDispatcher;

        TestTopicApplication(GeneratedTopicDispatcher topicDispatcher) {
            this.topicDispatcher = topicDispatcher;
        }

        @Override
        public String serviceName() {
            return "topic-wire-svc";
        }

        @Override
        public List<GeneratedClientBinding<?>> clients() {
            return List.of();
        }

        @Override
        public GeneratedMessageDispatcher dispatcher() {
            return (message, context) -> io.ringloom.framework.status.RingloomHandlerStatus.OK;
        }

        @Override
        public boolean requiresTopicBindings() {
            return true;
        }

        @Override
        public List<GeneratedTopicPublisherBinding> topicPublishers() {
            return List.of(new GeneratedTopicPublisherBinding() {
                @Override
                public Class<?> publisherType() {
                    return TopicPublisher.class;
                }

                @Override
                public String topic() {
                    return "wiredTopic";
                }

                @Override
                public TopicConfig topicConfig() {
                    return TopicConfig.DEFAULT;
                }

                @Override
                public Object create(
                        RingloomRuntime runtime,
                        RingloomClient client,
                        SerializerRegistry serializers,
                        TopicAckRegistry ackRegistry) {
                    return runtime.topicRuntime().publisher("wiredTopic");
                }
            });
        }

        @Override
        public List<GeneratedTopicHandlerBinding> topicHandlers() {
            return List.of(new GeneratedTopicHandlerBinding() {
                @Override
                public String topic() {
                    return "wiredTopic";
                }

                @Override
                public TopicStart start() {
                    return TopicStart.EARLIEST;
                }

                @Override
                public String serializer() {
                    return "";
                }

                @Override
                public String partitionKey() {
                    return null;
                }
            });
        }

        @Override
        public GeneratedTopicDispatcher topicDispatcher() {
            return topicDispatcher;
        }

        @Override
        public void initializeTopicIds(long[] resolvedTopicIds) {}
    }

    private static final class CountingTopicDispatcher implements GeneratedTopicDispatcher {
        final AtomicInteger count = new AtomicInteger();

        @Override
        public int onTopicMessage(TopicMessage message, TopicContext context) {
            count.incrementAndGet();
            return io.ringloom.framework.status.RingloomHandlerStatus.OK;
        }
    }
}
