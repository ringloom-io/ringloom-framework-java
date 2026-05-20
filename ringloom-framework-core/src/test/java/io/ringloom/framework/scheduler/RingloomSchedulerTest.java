// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.framework.config.SchedulerRuntimeConfig;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class RingloomSchedulerTest {
    private static final SchedulerRuntimeConfig CONFIG = new SchedulerRuntimeConfig(8, 1, 8, 2, 4);

    @Test
    void runsOneShotTasksOnPoll() throws Exception {
        // Given
        RingloomScheduler scheduler = new RingloomScheduler(CONFIG, null);
        AtomicInteger invocations = new AtomicInteger();

        // When
        long handle = scheduler.scheduleOnce(0, TimeUnit.NANOSECONDS, ignored -> invocations.incrementAndGet());
        int work = pollUntilWork(scheduler);

        // Then
        assertThat(work).isEqualTo(1);
        assertThat(invocations).hasValue(1);
        assertThat(scheduler.cancel(handle)).isFalse();
        assertThat(scheduler.activeTimerCount()).isZero();
    }

    @Test
    void reschedulesFixedRateTasksUntilCancelled() throws Exception {
        // Given
        RingloomScheduler scheduler = new RingloomScheduler(CONFIG, null);
        AtomicInteger invocations = new AtomicInteger();

        // When
        long handle =
                scheduler.scheduleAtFixedRate(0, 1, TimeUnit.MILLISECONDS, ignored -> invocations.incrementAndGet());
        int first = pollUntilWork(scheduler);
        int second = pollUntilWork(scheduler);
        boolean cancelled = scheduler.cancel(handle);

        // Then
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        assertThat(invocations).hasValue(2);
        assertThat(cancelled).isTrue();
        assertThat(scheduler.activeTimerCount()).isZero();
    }

    @Test
    void enforcesPollLimit() throws Exception {
        // Given
        RingloomScheduler scheduler = new RingloomScheduler(new SchedulerRuntimeConfig(8, 1, 8, 2, 1), null);
        AtomicInteger invocations = new AtomicInteger();

        // When
        scheduler.scheduleOnce(0, TimeUnit.NANOSECONDS, ignored -> invocations.incrementAndGet());
        scheduler.scheduleOnce(0, TimeUnit.NANOSECONDS, ignored -> invocations.incrementAndGet());
        int first = pollUntilWork(scheduler);
        int second = pollUntilWork(scheduler);

        // Then
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        assertThat(invocations).hasValue(2);
    }

    @Test
    void rejectsInvalidSchedulesAndCapacityOverflow() {
        // Given
        RingloomScheduler scheduler = new RingloomScheduler(new SchedulerRuntimeConfig(1, 1, 8, 2, 1), null);

        // When / Then
        assertThatThrownBy(() -> scheduler.scheduleOnce(-1, TimeUnit.NANOSECONDS, ignored -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("delay must be non-negative");
        assertThatThrownBy(() -> scheduler.scheduleAtFixedRate(0, 0, TimeUnit.NANOSECONDS, ignored -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("period must be positive");
        scheduler.scheduleOnce(1, TimeUnit.SECONDS, ignored -> {});
        assertThatThrownBy(() -> scheduler.scheduleOnce(1, TimeUnit.SECONDS, ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scheduler.maxTimers exhausted");
    }

    private static int pollUntilWork(RingloomScheduler scheduler) throws Exception {
        for (int i = 0; i < 16; i++) {
            int work = scheduler.poll(System.nanoTime());
            if (work != 0) {
                return work;
            }
            Thread.sleep(1);
        }
        return 0;
    }
}
