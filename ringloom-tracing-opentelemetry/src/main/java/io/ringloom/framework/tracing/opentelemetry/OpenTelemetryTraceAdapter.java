// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.tracing.opentelemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.ringloom.framework.annotation.RoutingMode;
import io.ringloom.framework.config.TracingRuntimeConfig;
import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.framework.tracing.ClientTraceContext;
import io.ringloom.framework.tracing.TraceAdapter;
import io.ringloom.framework.tracing.TraceScope;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * OpenTelemetry-backed {@link TraceAdapter}.
 *
 * <p>This adapter creates local messaging spans only. Cross-service propagation is reserved for a
 * later phase.
 */
public final class OpenTelemetryTraceAdapter implements TraceAdapter {
    private static final String MESSAGING_SYSTEM = "messaging.system";
    private static final String MESSAGING_DESTINATION_NAME = "messaging.destination.name";
    private static final String MESSAGING_OPERATION = "messaging.operation";
    private static final String RINGLOOM_CLIENT = "ringloom.client";
    private static final String RINGLOOM_TARGET_SERVICE = "ringloom.target_service";
    private static final String RINGLOOM_TEMPLATE_ID = "ringloom.template_id";
    private static final String RINGLOOM_ROUTING_MODE = "ringloom.routing_mode";
    private static final String RINGLOOM_PAYLOAD_BYTES = "ringloom.payload_bytes";
    private static final String RINGLOOM_STATUS = "ringloom.status";
    private static final String SYSTEM_NAME = "ringloom";

    private final Tracer tracer;
    private final TracingRuntimeConfig config;

    /**
     * Creates an OpenTelemetry trace adapter.
     *
     * @param tracer the OpenTelemetry tracer
     * @param config the RingLoom tracing configuration
     */
    public OpenTelemetryTraceAdapter(Tracer tracer, TracingRuntimeConfig config) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.config = config == null ? TracingRuntimeConfig.defaults() : config;
    }

    /**
     * Creates a builder for an OpenTelemetry trace adapter.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean shouldTraceSend(
            String clientName, String targetService, int templateId, RoutingMode routingMode, long payloadLength) {
        return shouldSample();
    }

    @Override
    public boolean shouldTraceReceive(MessageContext context) {
        return shouldSample();
    }

    @Override
    public TraceScope onSendStart(ClientTraceContext context) {
        Span span = tracer.spanBuilder("ringloom.send " + context.targetService() + "." + context.templateId())
                .setSpanKind(SpanKind.PRODUCER)
                .setAttribute(MESSAGING_SYSTEM, SYSTEM_NAME)
                .setAttribute(MESSAGING_DESTINATION_NAME, context.targetService())
                .setAttribute(MESSAGING_OPERATION, "publish")
                .setAttribute(RINGLOOM_CLIENT, context.clientName())
                .setAttribute(RINGLOOM_TARGET_SERVICE, context.targetService())
                .setAttribute(RINGLOOM_TEMPLATE_ID, context.templateId())
                .setAttribute(RINGLOOM_ROUTING_MODE, String.valueOf(context.routingMode()))
                .setAttribute(RINGLOOM_PAYLOAD_BYTES, context.payloadLength())
                .startSpan();
        return new SpanTraceScope(span, span.makeCurrent());
    }

    @Override
    public TraceScope onReceiveStart(MessageContext context) {
        Span span = tracer.spanBuilder("ringloom.receive " + context.templateId())
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute(MESSAGING_SYSTEM, SYSTEM_NAME)
                .setAttribute(MESSAGING_OPERATION, "process")
                .setAttribute(RINGLOOM_TEMPLATE_ID, context.templateId())
                .setAttribute(RINGLOOM_PAYLOAD_BYTES, context.payloadSegment().byteSize())
                .startSpan();
        return new SpanTraceScope(span, span.makeCurrent());
    }

    @Override
    public void onSendComplete(ClientTraceContext context, int status) {}

    @Override
    public void onHandlerComplete(MessageContext context, int status) {}

    private boolean shouldSample() {
        if (!config.enabled()) {
            return false;
        }
        return switch (config.sampler()) {
            case OFF -> false;
            case ALWAYS_ON -> true;
            case TRACE_ID_RATIO -> shouldSampleRatio();
        };
    }

    private boolean shouldSampleRatio() {
        double ratio = config.sampleRatio();
        if (ratio <= 0.0) {
            return false;
        }
        if (ratio >= 1.0) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < ratio;
    }

    /**
     * Builder for {@link OpenTelemetryTraceAdapter}.
     */
    public static final class Builder {
        private Tracer tracer;
        private TracingRuntimeConfig config = TracingRuntimeConfig.defaults();

        private Builder() {}

        /**
         * Sets the OpenTelemetry tracer.
         *
         * @param tracer the tracer
         * @return this builder
         */
        public Builder tracer(Tracer tracer) {
            this.tracer = Objects.requireNonNull(tracer, "tracer");
            return this;
        }

        /**
         * Sets the RingLoom tracing configuration.
         *
         * @param config the tracing configuration
         * @return this builder
         */
        public Builder config(TracingRuntimeConfig config) {
            this.config = config == null ? TracingRuntimeConfig.defaults() : config;
            return this;
        }

        /**
         * Builds the adapter.
         *
         * @return the adapter
         */
        public OpenTelemetryTraceAdapter build() {
            return new OpenTelemetryTraceAdapter(Objects.requireNonNull(tracer, "tracer"), config);
        }
    }

    private static final class SpanTraceScope implements TraceScope {
        private final Span span;
        private final Scope scope;
        private boolean completed;
        private boolean closed;

        private SpanTraceScope(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        @Override
        public void complete(int status) {
            if (closed) {
                return;
            }
            completed = true;
            span.setAttribute(RINGLOOM_STATUS, status);
            if (status == 0) {
                span.setStatus(StatusCode.OK);
            } else {
                span.setStatus(StatusCode.ERROR);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (!completed) {
                span.setStatus(StatusCode.ERROR);
            }
            try {
                scope.close();
            } finally {
                span.end();
            }
        }
    }
}
