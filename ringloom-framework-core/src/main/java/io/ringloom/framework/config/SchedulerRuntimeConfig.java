// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

/**
 * Configures the runtime scheduler backed by Agrona's deadline timer wheel.
 *
 * @param maxTimers maximum active timers tracked by the framework scheduler
 * @param tickResolutionNanos timer-wheel tick resolution in nanoseconds; must be a positive power
 *     of two
 * @param ticksPerWheel timer-wheel spoke count; must be a positive power of two
 * @param initialTickAllocation initial timer slots allocated per wheel tick; must be a positive
 *     power of two
 * @param pollLimit maximum timer expiries processed per control-loop duty cycle
 */
public record SchedulerRuntimeConfig(
        int maxTimers, long tickResolutionNanos, int ticksPerWheel, int initialTickAllocation, int pollLimit) {
    public SchedulerRuntimeConfig {
        maxTimers = maxTimers == 0 ? 1024 : maxTimers;
        tickResolutionNanos = tickResolutionNanos == 0 ? 1_048_576L : tickResolutionNanos;
        ticksPerWheel = ticksPerWheel == 0 ? 1024 : ticksPerWheel;
        initialTickAllocation = initialTickAllocation == 0 ? 16 : initialTickAllocation;
        pollLimit = pollLimit == 0 ? 64 : pollLimit;
        if (maxTimers <= 0) {
            throw new IllegalArgumentException("scheduler.maxTimers must be positive");
        }
        if (tickResolutionNanos <= 0 || (tickResolutionNanos & (tickResolutionNanos - 1)) != 0) {
            throw new IllegalArgumentException("scheduler.tickResolutionNanos must be a positive power of two");
        }
        if (ticksPerWheel <= 0 || (ticksPerWheel & (ticksPerWheel - 1)) != 0) {
            throw new IllegalArgumentException("scheduler.ticksPerWheel must be a positive power of two");
        }
        if (initialTickAllocation <= 0 || (initialTickAllocation & (initialTickAllocation - 1)) != 0) {
            throw new IllegalArgumentException("scheduler.initialTickAllocation must be a positive power of two");
        }
        if (pollLimit <= 0) {
            throw new IllegalArgumentException("scheduler.pollLimit must be positive");
        }
    }

    /**
     * Returns the default scheduler configuration.
     *
     * @return the framework defaults for scheduler execution
     */
    public static SchedulerRuntimeConfig defaults() {
        return new SchedulerRuntimeConfig(1024, 1_048_576L, 1024, 16, 64);
    }
}
