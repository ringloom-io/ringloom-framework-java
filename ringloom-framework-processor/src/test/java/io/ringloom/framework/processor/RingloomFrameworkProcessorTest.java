// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RingloomFrameworkProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesClientDispatcherAndApplication() throws Exception {
        // Given
        Path classes = Files.createDirectories(tempDir.resolve("classes"));
        Path generated = Files.createDirectories(tempDir.resolve("generated"));

        // When
        boolean success = compile(
                classes,
                generated,
                List.of(
                        source("test.OrdersApp", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomApplication;
                    @RingloomApplication(service = "orders")
                    public final class OrdersApp {}
                    """),
                        source("test.PricingClient", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomClient;
                    import io.ringloom.framework.annotation.RingloomRequest;
                    import java.lang.foreign.MemorySegment;
                    @RingloomClient(service = "pricing")
                    public interface PricingClient {
                      @RingloomRequest(templateId = 11)
                      int send(MemorySegment payload);
                    }
                    """),
                        source("test.Handlers", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomHandler;
                    import io.ringloom.framework.annotation.RingloomServiceComponent;
                    import io.ringloom.framework.dispatch.MessageContext;
                    import java.lang.foreign.MemorySegment;
                    @RingloomServiceComponent
                    public final class Handlers {
                      @RingloomHandler(templateId = 12)
                      public int onMessage(MemorySegment payload, MessageContext context) { return 0; }
                    }
                    """)));

        // Then
        assertThat(success).isTrue();
        assertThat(generated.resolve("test/PricingClient_RingloomClient.java")).exists();
        assertThat(generated.resolve("test/OrdersApp_RingloomApplication.java")).exists();
        assertThat(classes.resolve(
                        "META-INF/services/io.ringloom.framework.generated.GeneratedRingloomApplicationProvider"))
                .exists();
    }

    @Test
    void rejectsDuplicateHandlerTemplateIds() throws Exception {
        // Given
        Path classes = Files.createDirectories(tempDir.resolve("dup-classes"));
        Path generated = Files.createDirectories(tempDir.resolve("dup-generated"));

        // When
        boolean success = compile(classes, generated, List.of(source("test.DuplicateHandlers", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomHandler;
                    import io.ringloom.framework.annotation.RingloomServiceComponent;
                    @RingloomServiceComponent
                    public final class DuplicateHandlers {
                      @RingloomHandler(templateId = 7) public int a() { return 0; }
                      @RingloomHandler(templateId = 7) public int b() { return 0; }
                    }
                    """)));

        // Then
        assertThat(success).isFalse();
    }

    @Test
    void generatesSbeSerializerRegistrationsForDtosAndFlyweights() throws Exception {
        // Given
        Path classes = Files.createDirectories(tempDir.resolve("sbe-classes"));
        Path generated = Files.createDirectories(tempDir.resolve("sbe-generated"));

        // When
        boolean success = compile(
                classes,
                generated,
                List.of(
                        source("test.SbeApp", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomApplication;
                    @RingloomApplication(service = "sbe")
                    public final class SbeApp {}
                    """),
                        source("test.OrderDto", """
                    package test;
                    public final class OrderDto {
                      public int computeEncodedLength() { return 8; }
                      public static void encodeWith(OrderEncoder encoder, OrderDto dto) {}
                      public static void decodeWith(OrderDecoder decoder, OrderDto dto) {}
                    }
                    """),
                        source("test.OrderEncoder", """
                    package test;
                    import org.agrona.MutableDirectBuffer;
                    public final class OrderEncoder {
                      public static final int BLOCK_LENGTH = 8;
                      public OrderEncoder wrap(MutableDirectBuffer buffer, int offset) { return this; }
                      public int encodedLength() { return 8; }
                    }
                    """),
                        source("test.OrderDecoder", """
                    package test;
                    import org.agrona.DirectBuffer;
                    public final class OrderDecoder {
                      public static final int BLOCK_LENGTH = 8;
                      public static final int SCHEMA_VERSION = 1;
                      public OrderDecoder wrap(DirectBuffer buffer, int offset, int blockLength, int version) { return this; }
                      public long accountId() { return 42L; }
                    }
                    """),
                        source("test.OrderClient", """
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
                        source("test.Handlers", """
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
        assertThat(Files.readString(generated.resolve("test/SbeApp_RingloomApplication.java")))
                .contains("registerSerializers(SerializerRegistry.Builder builder)")
                .contains("builder.encoder(")
                .contains("\"sbe\"")
                .contains("test.OrderDto.class")
                .contains("test.OrderEncoder")
                .contains("builder.flyweight(")
                .contains("test.OrderDecoder.class")
                .contains("partitionKeyExtractors()")
                .contains("return decoded.accountId()");
        assertThat(Files.readString(generated.resolve("test/OrderClient_RingloomClient.java")))
                .contains("ThreadLocal<test.OrderDto> requestResponses")
                .contains("test.OrderDto.decodeWith(decoder, response)");
    }

    @Test
    void generatesSerializerBackedClientAndHandlerShapes() throws Exception {
        // Given
        Path classes = Files.createDirectories(tempDir.resolve("serializer-classes"));
        Path generated = Files.createDirectories(tempDir.resolve("serializer-generated"));

        // When
        boolean success = compile(
                classes,
                generated,
                List.of(
                        source("test.SerializerApp", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomApplication;
                    @RingloomApplication(service = "serializer")
                    public final class SerializerApp {}
                    """),
                        source("test.PriceRequest", """
                    package test;
                    public record PriceRequest(int id, PriceSide side) {}
                    """),
                        source("test.PriceSide", """
                    package test;
                    public enum PriceSide { BID, ASK }
                    """),
                        source("test.PricingClient", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomClient;
                    import io.ringloom.framework.annotation.RingloomRequest;
                    @RingloomClient(service = "pricing")
                    public interface PricingClient {
                      @RingloomRequest(templateId = 21, serializer = "fory")
                      int send(PriceRequest payload);
                    }
                    """),
                        source("test.Handlers", """
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
        assertThat(Files.readString(generated.resolve("test/SerializerApp_RingloomDispatcher.java")))
                .contains("FlyweightDecoder<test.PriceRequest>")
                .contains("context.runtime().resolveSerializerName(\"fory\")")
                .contains("serializers.flyweight(serializerName, test.PriceRequest.class)")
                .contains("MessageDecoder<test.PriceRequest>")
                .contains("serializers.decoder(serializerName, test.PriceRequest.class)");
        assertThat(Files.readString(generated.resolve("test/SerializerApp_RingloomApplication.java")))
                .contains("new io.ringloom.framework.serializer.fory.ForySerializerModule()")
                .contains(
                        "io.ringloom.framework.serializer.fory.ForySerializerConfig.from(serializers.entry(\"fory\"))")
                .contains("foryTypes.add(test.PriceRequest.class)")
                .contains("foryTypes.add(test.PriceSide.class)");
    }

    @Test
    void generatesDefaultForyRegistrationsForBlankRecordSerializers() throws Exception {
        // Given
        Path classes = Files.createDirectories(tempDir.resolve("default-fory-classes"));
        Path generated = Files.createDirectories(tempDir.resolve("default-fory-generated"));

        // When
        boolean success = compile(
                classes,
                generated,
                List.of(
                        source("test.DefaultForyApp", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomApplication;
                    @RingloomApplication(service = "orders")
                    public final class DefaultForyApp {}
                    """),
                        source("test.Order", """
                    package test;
                    public record Order(String symbol, OrderSide side) {}
                    """),
                        source("test.OrderSide", """
                    package test;
                    public enum OrderSide { BUY, SELL }
                    """),
                        source("test.OrderClient", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomClient;
                    import io.ringloom.framework.annotation.RingloomRequest;
                    @RingloomClient(service = "gateway")
                    public interface OrderClient {
                      @RingloomRequest(templateId = 41)
                      int send(Order payload);
                    }
                    """)));

        // Then
        assertThat(success).isTrue();
        assertThat(Files.readString(generated.resolve("test/DefaultForyApp_RingloomApplication.java")))
                .contains("if (\"fory\".equals(serializers.defaultSerializer()))")
                .contains("foryTypes.add(test.Order.class)")
                .contains("foryTypes.add(test.OrderSide.class)");
    }

    @Test
    void generatesVirtualThreadBlockingRequestAndDirectReplyShapes() throws Exception {
        // Given
        Path classes = Files.createDirectories(tempDir.resolve("rr-classes"));
        Path generated = Files.createDirectories(tempDir.resolve("rr-generated"));

        // When
        boolean success = compile(
                classes,
                generated,
                List.of(
                        source("test.RequestResponseApp", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomApplication;
                    @RingloomApplication(service = "terminal")
                    public final class RequestResponseApp {}
                    """),
                        source("test.PriceRequest", """
                    package test;
                    public record PriceRequest(String symbol) {}
                    """),
                        source("test.PriceQuote", """
                    package test;
                    public record PriceQuote(String symbol, long bid, long ask) {}
                    """),
                        source("test.PricingClient", """
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
                        source("test.TerminalClient", """
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
                .contains("serializers.decoder(responseSerializerName, test.PriceQuote.class)")
                .contains("awaiter.awaitNanos(timeoutNanos)");
        assertThat(Files.readString(generated.resolve("test/TerminalClient_RingloomClient.java")))
                .contains("tryClaimToRequest(")
                .contains("replyTo.sourceNodeId()")
                .contains("replyTo.correlationId()");
        assertThat(Files.readString(generated.resolve("test/RequestResponseApp_RingloomDispatcher.java")))
                .contains("resolvePendingResponse(context)")
                .contains("pending.decodeResponse(context)")
                .doesNotContain("context.correlationId() == 0")
                .contains(".complete(pending, context.correlationId(), context.templateId(), status, decoded)");
    }

    private static boolean compile(Path classes, Path generated, List<SimpleJavaFileObject> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        List<String> options = List.of(
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString(),
                "-s",
                generated.toString(),
                "-processor",
                RingloomFrameworkProcessor.class.getName());
        boolean success = compiler.getTask(null, null, diagnostics, options, null, sources)
                .call();
        for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                System.err.println(diagnostic);
            }
        }
        return success;
    }

    private static SimpleJavaFileObject source(String className, String content) {
        return new SimpleJavaFileObject(
                URI.create("string:///" + className.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }
}
