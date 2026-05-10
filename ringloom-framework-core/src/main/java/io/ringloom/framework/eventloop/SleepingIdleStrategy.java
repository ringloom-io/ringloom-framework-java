// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import java.util.concurrent.locks.LockSupport;

/**
 * Idle strategy that parks the current thread for a fixed duration when no work is available.
 */
public final class SleepingIdleStrategy implements IdleStrategy {
    private final long parkNanos;

    public SleepingIdleStrategy(long parkNanos) {
        if (parkNanos < 0) {
            throw new IllegalArgumentException("parkNanos must be non-negative");
        }
        this.parkNanos = parkNanos;
    }

    @Override
    public void idle(int workCount) {
        if (workCount <= 0 && parkNanos > 0) {
            LockSupport.parkNanos(parkNanos);
        }
    }

    @Override
    public void reset() {}
}
