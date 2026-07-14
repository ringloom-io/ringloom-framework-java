// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.topic;

import static org.assertj.core.api.Assertions.assertThat;

import io.ringloom.framework.TestBroker;
import io.ringloom.framework.config.RuntimeMode;
import io.ringloom.framework.config.topic.TopicsRuntimeConfig;
import io.ringloom.framework.metrics.UnavailableRingloomMetrics;
import io.ringloom.service.RingloomClient;
import io.ringloom.service.RingloomService;
import io.ringloom.service.RingloomStatus;
import io.ringloom.service.ServiceConfig;
import io.ringloom.service.TopicConfig;
import io.ringloom.service.TopicPublisher;
import io.ringloom.service.TopicStart;
import io.ringloom.service.TopicSubscription;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration tests for {@link TopicRuntime} against a topics-enabled test broker.
 *
 * <p>Activated by setting {@code -Dringloom.brokerBin=<path-to-ringloom-broker>} and
 * {@code -Dringloom.repoRoot=<path-to-ringloom-repo>}; skipped otherwise.
 */
@EnabledIfSystemProperty(named = "ringloom.brokerBin", matches = ".+")
final class TopicRuntimeIntegrationTest {
    private static final Path REPO_ROOT =
            Path.of(System.getProperty("ringloom.repoRoot", "/home/dragan/code/ringloom"));

    private TestBroker broker;
    private RingloomService service;
    private RingloomClient client;
    private TopicRuntime runtime;

    @AfterEach
    void tearDown() throws Exception {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
        if (client != null) {
            client.close();
            client = null;
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
    void registersSubscribesPollsAndAcksEndToEnd() throws Exception {
        startBrokerAndService();

        runtime = new TopicRuntime(
                service,
                TopicsRuntimeConfig.enabledDefaults(),
                null,
                UnavailableRingloomMetrics.INSTANCE,
                null,
                RuntimeMode.DEDICATED);

        TopicPublisher publisher = runtime.registerPublication(client, "quotes", TopicConfig.DEFAULT);
        TopicSubscription subscription = runtime.subscribe(client, "quotes", TopicStart.EARLIEST);

        assertThat(publisher.topicId()).isEqualTo(subscription.topicId()).isNotZero();
        assertThat(runtime.publisher("quotes")).isSameAs(publisher);
        assertThat(runtime.topicName(publisher.topicId())).isEqualTo("quotes");
        assertThat(runtime.hasSubscriptions()).isTrue();

        // Publish 4 messages (REPLICATE_ONCE) with known payload bytes.
        for (int i = 0; i < 4; i++) {
            publishByte(publisher, (byte) (i + 1));
        }
        waitForReplication(publisher, 4);

        // pollTopics with no source wired just advances cursors and returns the count.
        int drained = runtime.pollTopics(16);
        assertThat(drained).isEqualTo(4);
        assertThat(runtime.pollTopics(16)).as("subsequent poll is idle").isZero();
    }

    @Test
    void pollAckFeedbackAdvancesRegistries() throws Exception {
        startBrokerAndService();

        runtime = new TopicRuntime(
                service,
                TopicsRuntimeConfig.enabledDefaults(),
                null,
                UnavailableRingloomMetrics.INSTANCE,
                null,
                RuntimeMode.DEDICATED);

        TopicPublisher publisher = runtime.registerPublication(client, "trades", TopicConfig.DEFAULT);

        for (int i = 0; i < 3; i++) {
            publishByte(publisher, (byte) 1);
        }
        // Control-thread feedback poll should be safe to call repeatedly.
        runtime.pollAckFeedback();
        waitForReplication(publisher, 3);
        runtime.pollAckFeedback();
    }

    private void startBrokerAndService() throws Exception {
        Path workspace = Files.createTempDirectory("ringloom-topic-it-");
        broker = TestBroker.startTopicsEnabled(REPO_ROOT, workspace);
        ServiceConfig cfg = new ServiceConfig(
                "topic-it",
                workspace.resolve("storage").toString(),
                broker.group(),
                broker.nodeId(),
                false,
                0,
                0,
                0,
                false);
        service = RingloomService.start(cfg);
        client = service.createClient("topic-it");
        // Wait for self-discovery.
        for (int i = 0; i < 100 && client.targetServices().isEmpty(); i++) {
            service.pollControl(64);
            Thread.sleep(30);
        }
    }

    private static void publishByte(TopicPublisher publisher, byte value) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(ValueLayout.JAVA_BYTE).fill((byte) 0);
            segment.set(ValueLayout.JAVA_BYTE, 0, value);
            long[] outIndex = new long[1];
            int status = publisher.publish(segment, io.ringloom.service.TopicAckMode.REPLICATE_ONCE, 0L, outIndex);
            if (status != RingloomStatus.OK) {
                throw new IllegalStateException("publish failed status=" + status);
            }
        }
    }

    private void waitForReplication(TopicPublisher publisher, int expected) throws Exception {
        for (int i = 0; i < 300; i++) {
            service.pollControl(64);
            if (publisher.replicatedCount() >= expected) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("replicatedCount never reached " + expected + "; last=" + publisher.replicatedCount());
    }
}
