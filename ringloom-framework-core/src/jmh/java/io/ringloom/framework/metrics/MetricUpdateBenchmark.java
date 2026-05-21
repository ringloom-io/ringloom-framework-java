// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.metrics;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
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
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "-Xshare:off"})
@Threads(1)
public class MetricUpdateBenchmark {
    @Benchmark
    public long nativeVarHandleSet(MetricUpdateState state) {
        state.longHandle.setRelease(state.nativeSlot, 0L, 1L);
        return (long) state.longHandle.getAcquire(state.nativeSlot, 0L);
    }

    @Benchmark
    public long nativeVarHandleAtomicAdd(MetricUpdateState state) {
        return (long) state.longHandle.getAndAddRelease(state.nativeSlot, 0L, 1L);
    }

    @Benchmark
    public long heapLongAdderIncrement(MetricUpdateState state) {
        state.longAdder.increment();
        return state.longAdder.sum();
    }

    @State(Scope.Thread)
    public static class MetricUpdateState {
        final VarHandle longHandle = ValueLayout.JAVA_LONG.varHandle();
        final LongAdder longAdder = new LongAdder();
        Arena arena;
        MemorySegment nativeSlot;

        @Setup
        public void setUp() {
            arena = Arena.ofConfined();
            nativeSlot = arena.allocate(ValueLayout.JAVA_LONG);
        }

        @TearDown
        public void tearDown() {
            arena.close();
        }
    }
}
