// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ApplicationSourceGeneratorTest {

    @TempDir
    Path tempDir;

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
        assertThat(Files.readString(generated.resolve("test/SbeApp_RingloomApplication.java")))
                .contains("registerSerializers(SerializerRegistry.Builder builder)")
                .contains("builder.encoder(")
                .contains("\"sbe\"")
                .contains("test.OrderDto.class")
                .contains("test.OrderEncoder")
                .contains("builder.flyweight(")
                .contains("test.OrderDecoder.class")
                .contains("hasPartitionKeyExtractors()")
                .contains("public long partitionKey(")
                .contains("case 102 -> partitionKey102(message, context);")
                .contains("partitionKeyExtractors()")
                .contains("return decoded.accountId()");
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
        boolean success = ProcessorTestSupport.compile(
                classes,
                generated,
                List.of(
                        ProcessorTestSupport.source("test.DefaultForyApp", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomApplication;
                    @RingloomApplication(service = "orders")
                    public final class DefaultForyApp {}
                    """),
                        ProcessorTestSupport.source("test.Order", """
                    package test;
                    public record Order(String symbol, OrderSide side) {}
                    """),
                        ProcessorTestSupport.source("test.OrderSide", """
                    package test;
                    public enum OrderSide { BUY, SELL }
                    """),
                        ProcessorTestSupport.source("test.OrderClient", """
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
    void generatesScheduledMethodRegistration() throws Exception {
        // Given
        Path classes = Files.createDirectories(tempDir.resolve("schedule-classes"));
        Path generated = Files.createDirectories(tempDir.resolve("schedule-generated"));

        // When
        boolean success = ProcessorTestSupport.compile(
                classes,
                generated,
                List.of(
                        ProcessorTestSupport.source("test.ScheduleApp", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomApplication;
                    @RingloomApplication(service = "scheduler")
                    public final class ScheduleApp {}
                    """),
                        ProcessorTestSupport.source("test.ScheduledTasks", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomSchedule;
                    import io.ringloom.framework.annotation.RingloomServiceComponent;
                    @RingloomServiceComponent
                    public final class ScheduledTasks {
                      @RingloomSchedule(initialDelayMillis = 5, fixedRateMillis = 10)
                      public void tick() {}
                    }
                    """)));

        // Then
        assertThat(success).isTrue();
        assertThat(Files.readString(generated.resolve("test/ScheduleApp_RingloomApplication.java")))
                .contains("public void onRuntimeStarted(RingloomRuntime runtime)")
                .contains("runtime.scheduler().scheduleAtFixedRate(5L, 10L, java.util.concurrent.TimeUnit.MILLISECONDS")
                .contains("ignored -> component(test.ScheduledTasks.class).tick()")
                .contains("test.ScheduledTasks.class");
    }
}
