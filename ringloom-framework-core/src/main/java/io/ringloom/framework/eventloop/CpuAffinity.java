// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pins the current thread to a specific Linux CPU using {@code sched_setaffinity}.
 */
public final class CpuAffinity {
    /**
     * Maximum CPU id supported by the Linux {@code cpu_set_t} mask used here.
     */
    public static final int MAX_CPU_ID = 1023;

    private static final Logger LOGGER = LoggerFactory.getLogger(CpuAffinity.class);
    private static final boolean LINUX =
            System.getProperty("os.name", "").toLowerCase().contains("linux");
    private static final int CPU_SET_SIZE = 128;
    private static final AtomicBoolean UNSUPPORTED_WARNING_LOGGED = new AtomicBoolean();
    private static final MethodHandle SCHED_SET_AFFINITY;

    static {
        if (LINUX) {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup lookup = linker.defaultLookup();
                FunctionDescriptor descriptor = FunctionDescriptor.of(
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
                SCHED_SET_AFFINITY = linker.downcallHandle(
                        lookup.find("sched_setaffinity")
                                .orElseThrow(() -> new UnsatisfiedLinkError("sched_setaffinity not found")),
                        descriptor);
            } catch (UnsatisfiedLinkError ex) {
                throw new ExceptionInInitializerError("failed to initialize CPU affinity: " + ex.getMessage());
            }
        } else {
            SCHED_SET_AFFINITY = null;
        }
    }

    private CpuAffinity() {}

    /**
     * Pins the current thread to a CPU core.
     *
     * @param cpuId zero-based CPU id
     */
    public static void setCurrentThreadAffinity(int cpuId) {
        if (cpuId < 0 || cpuId > MAX_CPU_ID) {
            throw new IllegalArgumentException("cpuCore must be between 0 and " + MAX_CPU_ID);
        }
        if (!LINUX) {
            if (UNSUPPORTED_WARNING_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("CPU affinity is only supported on Linux; ignoring configured event-loop CPU pinning");
            }
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cpuSet = arena.allocate(CPU_SET_SIZE, 8);
            cpuSet.fill((byte) 0);
            cpuSet.set(ValueLayout.JAVA_BYTE, cpuId / 8, (byte) (1 << (cpuId % 8)));
            int result = (int) SCHED_SET_AFFINITY.invokeExact(0, (long) CPU_SET_SIZE, cpuSet);
            if (result != 0) {
                throw new IllegalStateException("sched_setaffinity failed for cpuCore " + cpuId);
            }
            LOGGER.debug(
                    "Pinned RingLoom event-loop thread {} to CPU {}",
                    Thread.currentThread().getName(),
                    cpuId);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IllegalStateException("failed to set CPU affinity for cpuCore " + cpuId, ex);
        }
    }

    /**
     * Returns whether CPU affinity is supported by this platform.
     *
     * @return {@code true} on Linux
     */
    public static boolean isSupported() {
        return LINUX;
    }
}
