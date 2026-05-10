// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

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
}
