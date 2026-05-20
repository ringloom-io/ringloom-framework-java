// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import org.agrona.concurrent.IdleStrategy;

/**
 * Idle strategy that spins briefly and then yields the thread.
 */
public final class YieldingIdleStrategy implements IdleStrategy {
    private final int spinCount;
    private int idleCount;

    public YieldingIdleStrategy(int spinCount) {
        if (spinCount < 0) {
            throw new IllegalArgumentException("spinCount must be non-negative");
        }
        this.spinCount = spinCount;
    }

    @Override
    public void idle(int workCount) {
        if (workCount > 0) {
            reset();
            return;
        }
        idle();
    }

    @Override
    public void idle() {
        if (idleCount++ < spinCount) {
            Thread.onSpinWait();
        } else {
            Thread.yield();
        }
    }

    @Override
    public void reset() {
        idleCount = 0;
    }

    @Override
    public String alias() {
        return "spin-yield";
    }
}
