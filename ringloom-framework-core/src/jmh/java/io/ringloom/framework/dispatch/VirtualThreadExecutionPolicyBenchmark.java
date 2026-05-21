// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import io.ringloom.framework.config.VirtualThreadExecutionConfig;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.service.RingloomMessage;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
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
public class VirtualThreadExecutionPolicyBenchmark {
    @Benchmark
    @OperationsPerInvocation(ExecutionPolicyBenchmarkSupport.BATCH_SIZE)
    public long virtualThreadThroughput(BenchmarkState state) {
        return state.dispatchBatch();
    }

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        @Param("10000")
        int maxInFlight;

        @Param("128")
        int payloadBytes;

        private final AtomicLong processed = new AtomicLong();
        private final AtomicLong checksum = new AtomicLong();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private Arena arena;
        private VirtualThreadExecutionPolicy policy;
        private RingloomMessage[] messages;
        private MessageContext context;
        private int cursor;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            GeneratedMessageDispatcher dispatcher = (message, context) -> {
                try {
                    checksum.addAndGet(
                            Byte.toUnsignedInt(context.payloadSegment().get(ValueLayout.JAVA_BYTE, 0)));
                    processed.incrementAndGet();
                    return 0;
                } catch (RuntimeException | Error ex) {
                    failure.compareAndSet(null, ex);
                    throw ex;
                }
            };
            arena = Arena.ofShared();
            policy = new VirtualThreadExecutionPolicy(dispatcher, new VirtualThreadExecutionConfig(maxInFlight));
            messages = ExecutionPolicyBenchmarkSupport.messages(arena, payloadBytes);
            context = new MessageContext();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (policy != null) {
                policy.close();
            }
            if (arena != null) {
                arena.close();
            }
        }

        long dispatchBatch() {
            long target = processed.get() + ExecutionPolicyBenchmarkSupport.BATCH_SIZE;
            for (int i = 0; i < ExecutionPolicyBenchmarkSupport.BATCH_SIZE; i++) {
                int status = policy.onMessage(
                        messages[cursor++ & (ExecutionPolicyBenchmarkSupport.MESSAGE_SET_SIZE - 1)], context);
                if (status <= 0) {
                    throw new IllegalStateException("virtual thread dispatch failed with status " + status);
                }
            }
            ExecutionPolicyBenchmarkSupport.waitFor(target, processed, failure);
            return checksum.get();
        }
    }
}
