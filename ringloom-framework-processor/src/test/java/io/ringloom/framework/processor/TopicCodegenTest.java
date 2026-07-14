// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TopicCodegenTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesTopicPublisherProxyAndDispatcherAndBindings() throws Exception {
        // Given
        Path classes = Files.createDirectories(tempDir.resolve("topic-classes"));
        Path generated = Files.createDirectories(tempDir.resolve("topic-generated"));

        // When
        boolean success = ProcessorTestSupport.compile(
                classes,
                generated,
                List.of(
                        ProcessorTestSupport.source("test.TopicApp", """
                                package test;
                                import io.ringloom.framework.annotation.RingloomApplication;
                                @RingloomApplication(service = "topics")
                                public final class TopicApp {}
                                """),
                        ProcessorTestSupport.source("test.Quote", """
                                package test;
                                public record Quote(String symbol, long price) {}
                                """),
                        ProcessorTestSupport.source("test.QuotesPublisher", """
                                package test;
                                import io.ringloom.framework.annotation.RingloomTopicPublish;
                                import io.ringloom.framework.annotation.RingloomTopicPublisher;
                                import io.ringloom.framework.topic.ack.AckCallback;
                                @RingloomTopicPublisher(topic = "quotes")
                                public interface QuotesPublisher {
                                  @RingloomTopicPublish(serializer = "fory")
                                  int publish(Quote payload);
                                  @RingloomTopicPublish(serializer = "fory", ackMode = io.ringloom.service.TopicAckMode.REPLICATE_ONCE)
                                  int publishAck(Quote payload, AckCallback callback, Object ctx);
                                }
                                """),
                        ProcessorTestSupport.source("test.Handlers", """
                                package test;
                                import io.ringloom.framework.annotation.RingloomServiceComponent;
                                import io.ringloom.framework.annotation.RingloomTopicHandler;
                                import io.ringloom.framework.topic.TopicContext;
                                @RingloomServiceComponent
                                public final class Handlers {
                                  @RingloomTopicHandler(topic = "quotes", serializer = "fory")
                                  public int onQuote(Quote payload, TopicContext context) { return 0; }
                                }
                                """)));

        // Then
        assertThat(success).as("compilation should succeed").isTrue();

        String publisherSrc = Files.readString(generated.resolve("test/QuotesPublisher_RingloomTopicPublisher.java"));
        assertThat(publisherSrc)
                .contains("public final class QuotesPublisher_RingloomTopicPublisher implements QuotesPublisher")
                .contains("private final TopicPublisher handle")
                .contains("private final TopicAckRegistry acks")
                .contains("public int publish(test.Quote payload)")
                .contains("handle.publish(scratch)")
                .contains("public int publishAck(test.Quote payload, AckCallback callback, Object userContext)")
                .contains("handle.publish(scratch, TopicAckMode.REPLICATE_ONCE, 0L, outIndex)")
                .contains("acks.register(outIndex[0], epoch, callback, userContext, 0L)");

        String dispatcherSrc = Files.readString(generated.resolve("test/TopicApp_RingloomTopicDispatcher.java"));
        assertThat(dispatcherSrc)
                .contains("implements GeneratedTopicDispatcher")
                .contains("private long quotesId")
                .contains("public void initializeTopicIds(long[] resolvedTopicIds)")
                .contains("this.quotesId = resolvedTopicIds[0]")
                .contains("if (topicId == quotesId)")
                .contains("return h0.onQuote(decoded, context)");

        String appSrc = Files.readString(generated.resolve("test/TopicApp_RingloomApplication.java"));
        assertThat(appSrc)
                .contains("public List<GeneratedTopicPublisherBinding> topicPublishers()")
                .contains("GeneratedTopicPublisherBinding")
                .contains("test.QuotesPublisher.class")
                .contains("public List<GeneratedTopicHandlerBinding> topicHandlers()")
                .contains("GeneratedTopicHandlerBinding")
                .contains("public GeneratedTopicDispatcher topicDispatcher()")
                .contains("public void initializeTopicIds(long[] resolvedTopicIds)");
    }

    @Test
    void rejectsNonInterfaceTopicPublisherAndBadHandlerSignatures() throws Exception {
        Path classes = Files.createDirectories(tempDir.resolve("topic-invalid-classes"));
        Path generated = Files.createDirectories(tempDir.resolve("topic-invalid-generated"));

        boolean success = ProcessorTestSupport.compile(
                classes,
                generated,
                List.of(
                        ProcessorTestSupport.source("test.InvalidApp", """
                                package test;
                                import io.ringloom.framework.annotation.RingloomApplication;
                                @RingloomApplication(service = "invalid")
                                public final class InvalidApp {}
                                """),
                        ProcessorTestSupport.source("test.BadPublisher", """
                                package test;
                                import io.ringloom.framework.annotation.RingloomTopicPublisher;
                                @RingloomTopicPublisher(topic = "bad")
                                public final class BadPublisher {}
                                """),
                        ProcessorTestSupport.source("test.BadHandlers", """
                                package test;
                                import io.ringloom.framework.annotation.RingloomServiceComponent;
                                import io.ringloom.framework.annotation.RingloomTopicHandler;
                                import io.ringloom.framework.topic.TopicContext;
                                @RingloomServiceComponent
                                public final class BadHandlers {
                                  @RingloomTopicHandler(topic = "bad", serializer = "fory")
                                  public void onBad(String payload, TopicContext context) {}
                                }
                                """)));

        assertThat(success)
                .as("compilation should fail on invalid topic annotations")
                .isFalse();
    }
}
