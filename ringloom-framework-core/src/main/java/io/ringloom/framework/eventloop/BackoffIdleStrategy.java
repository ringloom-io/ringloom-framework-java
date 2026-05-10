// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import java.util.concurrent.locks.LockSupport;

public final class BackoffIdleStrategy implements IdleStrategy {
    private final int maxSpins;
    private final int maxYields;
    private final long minParkNanos;
    private final long maxParkNanos;
    private int spins;
    private int yields;
    private long parkNanos;

    public BackoffIdleStrategy(int maxSpins, int maxYields, long minParkNanos, long maxParkNanos) {
        if (maxSpins < 0 || maxYields < 0 || minParkNanos < 0 || maxParkNanos < minParkNanos) {
            throw new IllegalArgumentException("invalid backoff settings");
        }
        this.maxSpins = maxSpins;
        this.maxYields = maxYields;
        this.minParkNanos = minParkNanos;
        this.maxParkNanos = maxParkNanos;
        this.parkNanos = minParkNanos;
    }

    @Override
    public void idle(int workCount) {
        if (workCount > 0) {
            reset();
            return;
        }
        if (spins++ < maxSpins) {
            Thread.onSpinWait();
            return;
        }
        if (yields++ < maxYields) {
            Thread.yield();
            return;
        }
        if (parkNanos > 0) {
            LockSupport.parkNanos(parkNanos);
            parkNanos = Math.min(maxParkNanos, Math.max(1, parkNanos << 1));
        }
    }

    @Override
    public void reset() {
        spins = 0;
        yields = 0;
        parkNanos = minParkNanos;
    }
}
