// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.tracing;

import io.ringloom.framework.annotation.RoutingMode;
import io.ringloom.framework.dispatch.MessageContext;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
        value = 1,
        jvmArgsAppend = {
            "--enable-native-access=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
            "-Xshare:off"
        })
@Threads(1)
public class GeneratedTracingHookBenchmark {
    @Benchmark
    public int disabledTracing(TracingState state) {
        return state.generatedSend(false, NoopTraceAdapter.INSTANCE);
    }

    @Benchmark
    public int enabledUnsampledTracing(TracingState state) {
        return state.generatedSend(true, state.unsampled);
    }

    @Benchmark
    public int enabledSampledTracing(TracingState state) {
        return state.generatedSend(true, state.sampled);
    }

    @State(Scope.Thread)
    public static class TracingState {
        final TraceAdapter unsampled = new BenchmarkTraceAdapter(false);
        final TraceAdapter sampled = new BenchmarkTraceAdapter(true);

        int generatedSend(boolean tracingEnabled, TraceAdapter traceAdapter) {
            ClientTraceContext traceContext = null;
            TraceScope traceScope = null;
            int status = 0;
            if (tracingEnabled
                    && traceAdapter.shouldTraceSend("PricingClient", "pricing", 101, RoutingMode.LEADER, 128)) {
                traceContext = new ClientTraceContext("PricingClient", "pricing", 101, RoutingMode.LEADER, 128);
                traceScope = traceAdapter.onSendStart(traceContext);
            }
            try {
                return status;
            } finally {
                if (traceContext != null) {
                    traceScope.complete(status);
                    traceAdapter.onSendComplete(traceContext, status);
                    traceScope.close();
                }
            }
        }
    }

    private static final class BenchmarkTraceAdapter implements TraceAdapter {
        private static final TraceScope SCOPE = new TraceScope() {
            @Override
            public void complete(int status) {}

            @Override
            public void close() {}
        };

        private final boolean sampled;

        private BenchmarkTraceAdapter(boolean sampled) {
            this.sampled = sampled;
        }

        @Override
        public boolean shouldTraceSend(
                String clientName, String targetService, int templateId, RoutingMode routingMode, long payloadLength) {
            return sampled;
        }

        @Override
        public boolean shouldTraceReceive(MessageContext context) {
            return sampled;
        }

        @Override
        public TraceScope onSendStart(ClientTraceContext context) {
            return SCOPE;
        }

        @Override
        public TraceScope onReceiveStart(MessageContext context) {
            return SCOPE;
        }

        @Override
        public void onSendComplete(ClientTraceContext context, int status) {}

        @Override
        public void onHandlerComplete(MessageContext context, int status) {}
    }
}
