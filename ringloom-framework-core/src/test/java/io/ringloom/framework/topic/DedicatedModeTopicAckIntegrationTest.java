// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.topic;

import static org.assertj.core.api.Assertions.assertThat;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.TestBroker;
import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.config.RingloomRuntimeConfig;
import io.ringloom.framework.config.RingloomServiceRuntimeConfig;
import io.ringloom.framework.config.RuntimeMode;
import io.ringloom.framework.config.topic.TopicsRuntimeConfig;
import io.ringloom.framework.generated.GeneratedClientBinding;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.framework.generated.GeneratedRingloomApplication;
import io.ringloom.framework.generated.GeneratedTopicPublisherBinding;
import io.ringloom.framework.metrics.UnavailableRingloomMetrics;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.framework.status.RingloomHandlerStatus;
import io.ringloom.framework.topic.ack.AckStatus;
import io.ringloom.framework.topic.ack.TopicAckRegistry;
import io.ringloom.service.RingloomClient;
import io.ringloom.service.RingloomStatus;
import io.ringloom.service.TopicAckMode;
import io.ringloom.service.TopicConfig;
import io.ringloom.service.TopicPublisher;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;

/**
 * Regression test (phase 16 §6.3): in {@code DEDICATED} mode the control agent must drive topic ack
 * feedback, so {@code replicate_once} acks complete without anyone calling
 * {@link RingloomRuntime#pollControl()}.
 */
@EnabledIfSystemProperty(named = "ringloom.brokerBin", matches = ".+")
final class DedicatedModeTopicAckIntegrationTest {
    private static final Path REPO_ROOT =
            Path.of(System.getProperty("ringloom.repoRoot", "/home/dragan/code/ringloom"));
    private static final String TOPIC = "dedicatedAcks";

    private TestBroker broker;
    private RingloomRuntime runtime;

    @AfterEach
    void tearDown() throws Exception {
        if (runtime != null) {
            runtime.close();
        }
        if (broker != null) {
            broker.close();
        }
    }

    @Test
    void topicAcksCompleteInDedicatedMode() throws Exception {
        Path workspace = Files.createTempDirectory("ringloom-topic-dedicated-");
        broker = TestBroker.startTopicsEnabled(REPO_ROOT, workspace);
        String storage = broker.storagePath() != null
                ? broker.storagePath()
                : workspace.resolve("storage").toString();
        runtime = new RingloomRuntime(
                new RingloomApplicationConfig(
                        new RingloomServiceRuntimeConfig(
                                "topic-dedicated", storage, broker.group(), broker.nodeId(), false, 0, 0, 0, false),
                        new RingloomRuntimeConfig(RuntimeMode.DEDICATED, null, null, null, null, null, false, null),
                        null,
                        Map.of(),
                        TopicsRuntimeConfig.enabledDefaults()),
                new PublisherApplication(),
                SerializerRegistry.EMPTY,
                UnavailableRingloomMetrics.INSTANCE,
                LoggerFactory.getLogger(DedicatedModeTopicAckIntegrationTest.class));
        runtime.start();
        runtime.startEventLoops(Thread.ofPlatform().name("topic-dedicated-", 0).factory());

        TopicPublisher publisher = runtime.topicRuntime().publisher(TOPIC);
        TopicAckRegistry registry = runtime.topicRuntime().ackRegistry(publisher.topicId());
        AtomicReference<AckStatus> result = new AtomicReference<>();
        long[] outIndex = new long[1];
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment payload = arena.allocate(8);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            int status;
            do {
                status = publisher.publish(payload, TopicAckMode.REPLICATE_ONCE, 0L, outIndex);
                if (status != RingloomStatus.OK) {
                    Thread.sleep(10);
                }
            } while (status != RingloomStatus.OK && System.nanoTime() < deadline);
            assertThat(status).isEqualTo(RingloomStatus.OK);
        }
        assertThat(registry.register(
                        outIndex[0], publisher.leaderEpoch(), (index, status, ctx) -> result.set(status), null, 0))
                .isTrue();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (result.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(result.get()).as("ack completed by the control agent").isEqualTo(AckStatus.ACKED);
    }

    private static final class PublisherApplication implements GeneratedRingloomApplication {
        @Override
        public String serviceName() {
            return "topic-dedicated";
        }

        @Override
        public List<GeneratedClientBinding<?>> clients() {
            return List.of();
        }

        @Override
        public GeneratedMessageDispatcher dispatcher() {
            return (message, context) -> RingloomHandlerStatus.OK;
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
                    return TOPIC;
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
                    return runtime.topicRuntime().publisher(TOPIC);
                }
            });
        }
    }
}
