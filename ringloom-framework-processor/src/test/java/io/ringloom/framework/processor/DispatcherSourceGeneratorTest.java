// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DispatcherSourceGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesClientDispatcherAndApplication() throws Exception {
        // Given
        Path classes = Files.createDirectories(tempDir.resolve("classes"));
        Path generated = Files.createDirectories(tempDir.resolve("generated"));

        // When
        boolean success = ProcessorTestSupport.compile(
                classes,
                generated,
                List.of(
                        ProcessorTestSupport.source("test.OrdersApp", ProcessorTestSupport.ORDERS_APP_SRC),
                        ProcessorTestSupport.source("test.PricingClient", ProcessorTestSupport.PRICING_CLIENT_SRC),
                        ProcessorTestSupport.source("test.Handlers", ProcessorTestSupport.HANDLERS_SRC)));

        // Then
        assertThat(success).isTrue();
        assertThat(Files.readString(generated.resolve("test/OrdersApp_RingloomDispatcher.java")))
                .contains("extractPayloadPrefix(context)")
                .contains("tracedDispatchOrResolve(context)")
                .contains("traceScope.complete(status)")
                .contains("traceAdapter.onHandlerComplete(context, status)");
    }

    @Test
    void generatesSerializerBackedClientAndHandlerShapes() throws Exception {
        // Given
        Path classes = Files.createDirectories(tempDir.resolve("serializer-classes"));
        Path generated = Files.createDirectories(tempDir.resolve("serializer-generated"));

        // When
        boolean success = ProcessorTestSupport.compile(
                classes,
                generated,
                List.of(
                        ProcessorTestSupport.source("test.SerializerApp", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomApplication;
                    @RingloomApplication(service = "serializer")
                    public final class SerializerApp {}
                    """),
                        ProcessorTestSupport.source("test.PriceRequest", """
                    package test;
                    public record PriceRequest(int id, PriceSide side) {}
                    """),
                        ProcessorTestSupport.source("test.PriceSide", """
                    package test;
                    public enum PriceSide { BID, ASK }
                    """),
                        ProcessorTestSupport.source("test.PricingClient", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomClient;
                    import io.ringloom.framework.annotation.RingloomRequest;
                    @RingloomClient(service = "pricing")
                    public interface PricingClient {
                      @RingloomRequest(templateId = 21, serializer = "fory")
                      int send(PriceRequest payload);
                    }
                    """),
                        ProcessorTestSupport.source("test.Handlers", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomHandler;
                    import io.ringloom.framework.annotation.RingloomServiceComponent;
                    import io.ringloom.framework.dispatch.MessageContext;
                    @RingloomServiceComponent
                    public final class Handlers {
                      @RingloomHandler(templateId = 22, serializer = "fory")
                      public int onMessage(PriceRequest payload, MessageContext context) { return payload.id(); }
                    }
                    """)));

        // Then
        assertThat(success).isTrue();
        assertThat(Files.readString(generated.resolve("test/SerializerApp_RingloomDispatcher.java")))
                .contains("FlyweightDecoder<test.PriceRequest>")
                .contains("context.runtime().resolveSerializerName(\"fory\")")
                .contains("serializers.flyweight(serializerName, test.PriceRequest.class)")
                .contains("MessageDecoder<test.PriceRequest>")
                .contains("serializers.decoder(serializerName, test.PriceRequest.class)");
    }

    @Test
    void generatesVirtualThreadBlockingRequestAndDirectReplyShapes() throws Exception {
        // Given
        Path classes = Files.createDirectories(tempDir.resolve("rr-classes"));
        Path generated = Files.createDirectories(tempDir.resolve("rr-generated"));

        // When
        boolean success = ProcessorTestSupport.compile(
                classes,
                generated,
                List.of(
                        ProcessorTestSupport.source("test.RequestResponseApp", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomApplication;
                    @RingloomApplication(service = "terminal")
                    public final class RequestResponseApp {}
                    """),
                        ProcessorTestSupport.source("test.PriceRequest", """
                    package test;
                    public record PriceRequest(String symbol) {}
                    """),
                        ProcessorTestSupport.source("test.PriceQuote", """
                    package test;
                    public record PriceQuote(String symbol, long bid, long ask) {}
                    """),
                        ProcessorTestSupport.source("test.PricingClient", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomClient;
                    import io.ringloom.framework.annotation.RingloomRequest;
                    import io.ringloom.framework.annotation.RequestMode;
                    import io.ringloom.framework.request.RequestTimeout;
                    import io.ringloom.framework.request.RingloomRequestException;
                    @RingloomClient(service = "pricing")
                    public interface PricingClient {
                      @RingloomRequest(
                          templateId = 31,
                          responseTemplateId = 32,
                          serializer = "fory",
                          mode = RequestMode.VIRTUAL_THREAD_BLOCKING)
                      PriceQuote requestQuote(PriceRequest payload, RequestTimeout timeout)
                          throws RingloomRequestException, InterruptedException;
                    }
                    """),
                        ProcessorTestSupport.source("test.TerminalClient", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomClient;
                    import io.ringloom.framework.annotation.RingloomRequest;
                    import io.ringloom.framework.annotation.RoutingMode;
                    import io.ringloom.framework.dispatch.MessageContext;
                    @RingloomClient(service = "terminal")
                    public interface TerminalClient {
                      @RingloomRequest(templateId = 32, serializer = "fory", routing = RoutingMode.DIRECT)
                      int replyQuote(PriceQuote payload, MessageContext replyTo);
                    }
                    """)));

        // Then
        assertThat(success).isTrue();
        assertThat(Files.readString(generated.resolve("test/RequestResponseApp_RingloomDispatcher.java")))
                .contains("resolvePendingResponse(context)")
                .contains("pending.decodeResponse(context)")
                .doesNotContain("context.correlationId() == 0")
                .contains(".complete(pending, context.correlationId(), context.templateId(), status, decoded)");
    }
}
