// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClientSourceGeneratorTest {

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
        assertThat(Files.readString(generated.resolve("test/PricingClient_RingloomClient.java")))
                .contains("runtime.traceAdapter()")
                .contains("shouldTraceSend(")
                .contains("traceScope.complete(status)")
                .contains("traceAdapter.onSendComplete(traceContext, status)");
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
        assertThat(Files.readString(generated.resolve("test/PricingClient_RingloomClient.java")))
                .contains("MessageEncoder<test.PriceRequest>")
                .contains("runtime.resolveSerializerName(\"fory\")")
                .contains("serializers.encoder(serializerName, test.PriceRequest.class)");
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
        assertThat(Files.readString(generated.resolve("test/PricingClient_RingloomClient.java")))
                .contains("tryClaimRequest(31, pending.correlationId()")
                .contains("pending.prepare(")
                .contains("payloadPrefixLength(traceContext)")
                .contains("writePayloadPrefix(")
                .contains("serializers.decoder(responseSerializerName, test.PriceQuote.class)")
                .contains("awaiter.awaitNanos(timeoutNanos)");
        assertThat(Files.readString(generated.resolve("test/TerminalClient_RingloomClient.java")))
                .contains("tryClaimToRequest(")
                .contains("replyTo.sourceNodeId()")
                .contains("replyTo.correlationId()");
    }

    @Test
    void generatesSbeSerializerRegistrationsForDtosAndFlyweights() throws Exception {
        // Given
        Path classes = Files.createDirectories(tempDir.resolve("sbe-classes"));
        Path generated = Files.createDirectories(tempDir.resolve("sbe-generated"));

        // When
        boolean success = ProcessorTestSupport.compile(
                classes,
                generated,
                List.of(
                        ProcessorTestSupport.source("test.SbeApp", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomApplication;
                    @RingloomApplication(service = "sbe")
                    public final class SbeApp {}
                    """),
                        ProcessorTestSupport.source("test.OrderDto", """
                    package test;
                    public final class OrderDto {
                      public int computeEncodedLength() { return 8; }
                      public static void encodeWith(OrderEncoder encoder, OrderDto dto) {}
                      public static void decodeWith(OrderDecoder decoder, OrderDto dto) {}
                    }
                    """),
                        ProcessorTestSupport.source("test.OrderEncoder", """
                    package test;
                    import org.agrona.MutableDirectBuffer;
                    public final class OrderEncoder {
                      public static final int BLOCK_LENGTH = 8;
                      public OrderEncoder wrap(MutableDirectBuffer buffer, int offset) { return this; }
                      public int encodedLength() { return 8; }
                    }
                    """),
                        ProcessorTestSupport.source("test.OrderDecoder", """
                    package test;
                    import org.agrona.DirectBuffer;
                    public final class OrderDecoder {
                      public static final int BLOCK_LENGTH = 8;
                      public static final int SCHEMA_VERSION = 1;
                      public OrderDecoder wrap(DirectBuffer buffer, int offset, int blockLength, int version) { return this; }
                      public long accountId() { return 42L; }
                    }
                    """),
                        ProcessorTestSupport.source("test.OrderClient", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomClient;
                    import io.ringloom.framework.annotation.RingloomRequest;
                    import io.ringloom.framework.annotation.RequestMode;
                    import io.ringloom.framework.request.RequestTimeout;
                    import io.ringloom.framework.request.RingloomRequestException;
                    @RingloomClient(service = "gateway")
                    public interface OrderClient {
                      @RingloomRequest(templateId = 101)
                      int send(OrderDto payload);
                      @RingloomRequest(templateId = 101, responseTemplateId = 102, mode = RequestMode.VIRTUAL_THREAD_BLOCKING)
                      OrderDto request(OrderDto payload, RequestTimeout timeout)
                          throws RingloomRequestException, InterruptedException;
                    }
                    """),
                        ProcessorTestSupport.source("test.Handlers", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomHandler;
                    import io.ringloom.framework.annotation.RingloomPartitionKey;
                    import io.ringloom.framework.annotation.RingloomServiceComponent;
                    import io.ringloom.framework.dispatch.MessageContext;
                    @RingloomServiceComponent
                    public final class Handlers {
                      @RingloomHandler(templateId = 102)
                      public int onMessage(@RingloomPartitionKey("accountId") OrderDecoder payload, MessageContext context) { return 0; }
                    }
                    """)));

        // Then
        assertThat(success).isTrue();
        assertThat(Files.readString(generated.resolve("test/OrderClient_RingloomClient.java")))
                .contains("ThreadLocal<test.OrderDto> requestResponses")
                .contains("test.OrderDto.decodeWith(decoder, response)");
    }
}
