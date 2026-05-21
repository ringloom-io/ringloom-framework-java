// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.tracing.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.ringloom.framework.annotation.RoutingMode;
import io.ringloom.framework.config.TracingPropagationMode;
import io.ringloom.framework.config.TracingRuntimeConfig;
import io.ringloom.framework.config.TracingSamplerKind;
import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.framework.tracing.ClientTraceContext;
import io.ringloom.framework.tracing.TraceScope;
import java.lang.foreign.MemorySegment;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class OpenTelemetryTraceAdapterTest {
    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;

    @AfterEach
    void closeTracerProvider() {
        if (tracerProvider != null) {
            tracerProvider.close();
        }
    }

    @Test
    void disabledConfigDoesNotSample() {
        // Given
        OpenTelemetryTraceAdapter adapter = adapter(TracingRuntimeConfig.defaults());

        // When / Then
        assertThat(adapter.shouldTraceSend("client", "pricing", 1, RoutingMode.LEADER, 16))
                .isFalse();
        assertThat(adapter.shouldTraceReceive(messageContext())).isFalse();
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void ratioZeroDoesNotSampleAndRatioOneSamples() {
        // Given
        OpenTelemetryTraceAdapter off = adapter(new TracingRuntimeConfig(
                true, TracingSamplerKind.TRACE_ID_RATIO, 0.0, TracingPropagationMode.NONE, true));
        OpenTelemetryTraceAdapter on = adapter(new TracingRuntimeConfig(
                true, TracingSamplerKind.TRACE_ID_RATIO, 1.0, TracingPropagationMode.NONE, true));

        // When / Then
        assertThat(off.shouldTraceSend("client", "pricing", 1, RoutingMode.LEADER, 16))
                .isFalse();
        assertThat(on.shouldTraceSend("client", "pricing", 1, RoutingMode.LEADER, 16))
                .isTrue();
    }

    @Test
    void recordsSendSpanAttributesAndStatus() {
        // Given
        OpenTelemetryTraceAdapter adapter = adapter(alwaysOn());
        ClientTraceContext context = new ClientTraceContext("PricingClient", "pricing", 101, RoutingMode.LEADER, 128);

        // When
        TraceScope scope = adapter.onSendStart(context);
        scope.complete(0);
        scope.close();

        // Then
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        SpanData span = spans.getFirst();
        assertThat(span.getName()).isEqualTo("ringloom.send pricing.101");
        assertThat(span.getKind()).isEqualTo(SpanKind.PRODUCER);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.system")))
                .isEqualTo("ringloom");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")))
                .isEqualTo("pricing");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.operation")))
                .isEqualTo("publish");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("ringloom.client")))
                .isEqualTo("PricingClient");
        assertThat(span.getAttributes().get(AttributeKey.longKey("ringloom.template_id")))
                .isEqualTo(101L);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("ringloom.routing_mode")))
                .isEqualTo("LEADER");
        assertThat(span.getAttributes().get(AttributeKey.longKey("ringloom.payload_bytes")))
                .isEqualTo(128L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("ringloom.status")))
                .isZero();
    }

    @Test
    void recordsReceiveSpanAttributesAndErrorStatus() {
        // Given
        OpenTelemetryTraceAdapter adapter = adapter(alwaysOn());
        MessageContext context = messageContext();

        // When
        TraceScope scope = adapter.onReceiveStart(context);
        scope.complete(7);
        scope.close();

        // Then
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        SpanData span = spans.getFirst();
        assertThat(span.getName()).isEqualTo("ringloom.receive 77");
        assertThat(span.getKind()).isEqualTo(SpanKind.CONSUMER);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.system")))
                .isEqualTo("ringloom");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.operation")))
                .isEqualTo("process");
        assertThat(span.getAttributes().get(AttributeKey.longKey("ringloom.template_id")))
                .isEqualTo(77L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("ringloom.payload_bytes")))
                .isEqualTo(16L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("ringloom.status")))
                .isEqualTo(7L);
    }

    @Test
    void builderRequiresTracer() {
        // Given / When / Then
        assertThatThrownBy(() -> OpenTelemetryTraceAdapter.builder().build())
                .isInstanceOf(NullPointerException.class)
                .hasMessage("tracer");
    }

    private OpenTelemetryTraceAdapter adapter(TracingRuntimeConfig config) {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry openTelemetry =
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        return OpenTelemetryTraceAdapter.builder()
                .tracer(openTelemetry.getTracer("ringloom-test"))
                .config(config)
                .build();
    }

    private static TracingRuntimeConfig alwaysOn() {
        return new TracingRuntimeConfig(true, TracingSamplerKind.ALWAYS_ON, 1.0, TracingPropagationMode.NONE, true);
    }

    private static MessageContext messageContext() {
        MessageContext context = new MessageContext();
        context.updateCopied(
                42, (short) 1, (short) 2, (short) 3, (short) 4, 77, 0, MemorySegment.ofArray(new byte[16]));
        return context;
    }
}
