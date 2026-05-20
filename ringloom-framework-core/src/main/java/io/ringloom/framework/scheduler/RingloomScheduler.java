// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.scheduler;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.config.SchedulerRuntimeConfig;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.agrona.DeadlineTimerWheel;
import org.agrona.collections.Long2LongHashMap;

/**
 * Low-allocation scheduler owned by a {@link RingloomRuntime}.
 *
 * <p>The scheduler uses Agrona's non-thread-safe {@link DeadlineTimerWheel} behind a short
 * synchronized section so timers can be scheduled or cancelled from any application thread while
 * expiry polling remains on the runtime control thread.
 */
public final class RingloomScheduler {
    private static final long MISSING_SLOT = -1L;
    private static final byte FREE = 0;
    private static final byte SCHEDULED = 1;
    private static final byte EXPIRED = 2;
    private static final byte CANCELLED = 3;
    private static final byte ONCE = 1;
    private static final byte FIXED_RATE = 2;
    private static final byte FIXED_DELAY = 3;

    private final RingloomRuntime runtime;
    private final SchedulerRuntimeConfig config;
    private final DeadlineTimerWheel wheel;
    private final Long2LongHashMap slotsByTimerId;
    private final RingloomScheduledTask[] tasks;
    private final RingloomScheduledTask[] expiredTasks;
    private final long[] handles;
    private final long[] expiredHandles;
    private final long[] generations;
    private final long[] deadlinesNanos;
    private final long[] periodsNanos;
    private final long[] timerIds;
    private final byte[] states;
    private final byte[] modes;
    private final int[] freeSlots;
    private final int[] expiredSlots;
    private int freeTop;
    private int expiredCount;

    public RingloomScheduler(SchedulerRuntimeConfig config, RingloomRuntime runtime) {
        this.config = Objects.requireNonNull(config, "config");
        this.runtime = runtime;
        this.wheel = new DeadlineTimerWheel(
                TimeUnit.NANOSECONDS,
                System.nanoTime(),
                config.tickResolutionNanos(),
                config.ticksPerWheel(),
                config.initialTickAllocation());
        this.slotsByTimerId = new Long2LongHashMap(config.maxTimers() * 2, 0.6f, MISSING_SLOT);
        this.tasks = new RingloomScheduledTask[config.maxTimers()];
        this.expiredTasks = new RingloomScheduledTask[config.pollLimit()];
        this.handles = new long[config.maxTimers()];
        this.expiredHandles = new long[config.pollLimit()];
        this.generations = new long[config.maxTimers()];
        this.deadlinesNanos = new long[config.maxTimers()];
        this.periodsNanos = new long[config.maxTimers()];
        this.timerIds = new long[config.maxTimers()];
        this.states = new byte[config.maxTimers()];
        this.modes = new byte[config.maxTimers()];
        this.freeSlots = new int[config.maxTimers()];
        this.expiredSlots = new int[config.pollLimit()];
        this.freeTop = config.maxTimers();
        for (int i = 0; i < freeSlots.length; i++) {
            freeSlots[i] = freeSlots.length - 1 - i;
            timerIds[i] = DeadlineTimerWheel.NULL_DEADLINE;
        }
    }

    /**
     * Schedules a one-shot task.
     *
     * @param delay delay before first execution
     * @param unit time unit for the delay
     * @param task task to invoke on the control thread
     * @return cancellation handle
     */
    public long scheduleOnce(long delay, TimeUnit unit, RingloomScheduledTask task) {
        long delayNanos = nanos(delay, unit, "delay");
        return schedule(delayNanos, 0, ONCE, task);
    }

    /**
     * Schedules a periodic task with fixed-rate deadlines. Missed intervals are skipped when the
     * control thread falls behind.
     *
     * @param initialDelay delay before first execution
     * @param period period between scheduled deadlines
     * @param unit time unit for both values
     * @param task task to invoke on the control thread
     * @return cancellation handle
     */
    public long scheduleAtFixedRate(long initialDelay, long period, TimeUnit unit, RingloomScheduledTask task) {
        long initialDelayNanos = nanos(initialDelay, unit, "initialDelay");
        long periodNanos = positiveNanos(period, unit, "period");
        return schedule(initialDelayNanos, periodNanos, FIXED_RATE, task);
    }

    /**
     * Schedules a periodic task with fixed delay after each callback completes.
     *
     * @param initialDelay delay before first execution
     * @param delay delay after each callback completes
     * @param unit time unit for both values
     * @param task task to invoke on the control thread
     * @return cancellation handle
     */
    public long scheduleWithFixedDelay(long initialDelay, long delay, TimeUnit unit, RingloomScheduledTask task) {
        long initialDelayNanos = nanos(initialDelay, unit, "initialDelay");
        long delayNanos = positiveNanos(delay, unit, "delay");
        return schedule(initialDelayNanos, delayNanos, FIXED_DELAY, task);
    }

    /**
     * Cancels a scheduled task.
     *
     * @param handle handle returned by a schedule method
     * @return {@code true} if the handle was active
     */
    public synchronized boolean cancel(long handle) {
        int slot = slot(handle);
        if (!validHandle(slot, handle)) {
            return false;
        }
        if (states[slot] == SCHEDULED) {
            long timerId = timerIds[slot];
            if (timerId != DeadlineTimerWheel.NULL_DEADLINE) {
                wheel.cancelTimer(timerId);
                slotsByTimerId.remove(timerId);
            }
            release(slot);
            return true;
        }
        if (states[slot] == EXPIRED) {
            states[slot] = CANCELLED;
            return true;
        }
        return false;
    }

    /**
     * Polls expired timers. This method is intended to be called by the control loop.
     *
     * @param nowNanos current monotonic time in nanoseconds
     * @return number of callbacks invoked
     * @throws Exception when a scheduled callback fails
     */
    public int poll(long nowNanos) throws Exception {
        int count;
        synchronized (this) {
            expiredCount = 0;
            wheel.poll(nowNanos, this::onTimerExpiry, expiredSlots.length);
            count = expiredCount;
        }
        for (int i = 0; i < count; i++) {
            int slot = expiredSlots[i];
            long handle = expiredHandles[i];
            RingloomScheduledTask task = expiredTasks[i];
            expiredTasks[i] = null;
            try {
                task.onSchedule(runtime);
            } finally {
                completeExpired(slot, handle);
            }
        }
        return count;
    }

    public synchronized long activeTimerCount() {
        return wheel.timerCount();
    }

    public SchedulerRuntimeConfig config() {
        return config;
    }

    public synchronized void clear() {
        wheel.clear();
        slotsByTimerId.clear();
        for (int i = 0; i < states.length; i++) {
            if (states[i] != FREE) {
                release(i);
            }
        }
    }

    private synchronized long schedule(long delayNanos, long periodNanos, byte mode, RingloomScheduledTask task) {
        Objects.requireNonNull(task, "task");
        if (freeTop == 0) {
            throw new IllegalStateException("scheduler.maxTimers exhausted");
        }
        int slot = freeSlots[--freeTop];
        long deadline = Math.addExact(System.nanoTime(), delayNanos);
        generations[slot]++;
        long handle = (generations[slot] << 32) | Integer.toUnsignedLong(slot);
        tasks[slot] = task;
        handles[slot] = handle;
        periodsNanos[slot] = periodNanos;
        modes[slot] = mode;
        states[slot] = SCHEDULED;
        try {
            scheduleWheel(slot, deadline);
        } catch (RuntimeException | Error ex) {
            release(slot);
            throw ex;
        }
        return handle;
    }

    private boolean onTimerExpiry(TimeUnit timeUnit, long now, long timerId) {
        long slotValue = slotsByTimerId.remove(timerId);
        if (slotValue == MISSING_SLOT) {
            return true;
        }
        int slot = (int) slotValue;
        timerIds[slot] = DeadlineTimerWheel.NULL_DEADLINE;
        states[slot] = EXPIRED;
        if (modes[slot] == FIXED_RATE) {
            long nextDeadline = deadlinesNanos[slot] + periodsNanos[slot];
            while (nextDeadline <= now) {
                nextDeadline = Math.addExact(nextDeadline, periodsNanos[slot]);
            }
            deadlinesNanos[slot] = nextDeadline;
        }
        expiredTasks[expiredCount] = tasks[slot];
        expiredHandles[expiredCount] = handles[slot];
        expiredSlots[expiredCount++] = slot;
        return true;
    }

    private void completeExpired(int slot, long handle) {
        synchronized (this) {
            if (!validHandle(slot, handle)) {
                return;
            }
            if (states[slot] == CANCELLED || modes[slot] == ONCE) {
                release(slot);
                return;
            }
            long deadline = modes[slot] == FIXED_DELAY
                    ? Math.addExact(System.nanoTime(), periodsNanos[slot])
                    : deadlinesNanos[slot];
            states[slot] = SCHEDULED;
            try {
                scheduleWheel(slot, deadline);
            } catch (RuntimeException | Error ex) {
                release(slot);
                throw ex;
            }
        }
    }

    private void scheduleWheel(int slot, long deadline) {
        long timerId = wheel.scheduleTimer(deadline);
        timerIds[slot] = timerId;
        deadlinesNanos[slot] = deadline;
        slotsByTimerId.put(timerId, slot);
    }

    private void release(int slot) {
        tasks[slot] = null;
        handles[slot] = 0;
        periodsNanos[slot] = 0;
        deadlinesNanos[slot] = 0;
        timerIds[slot] = DeadlineTimerWheel.NULL_DEADLINE;
        modes[slot] = 0;
        states[slot] = FREE;
        freeSlots[freeTop++] = slot;
    }

    private boolean validHandle(int slot, long handle) {
        return slot >= 0 && slot < states.length && states[slot] != FREE && handles[slot] == handle;
    }

    private static int slot(long handle) {
        return (int) handle;
    }

    private static long nanos(long value, TimeUnit unit, String name) {
        Objects.requireNonNull(unit, "unit");
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return unit.toNanos(value);
    }

    private static long positiveNanos(long value, TimeUnit unit, String name) {
        long nanos = nanos(value, unit, name);
        if (nanos <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return nanos;
    }
}
