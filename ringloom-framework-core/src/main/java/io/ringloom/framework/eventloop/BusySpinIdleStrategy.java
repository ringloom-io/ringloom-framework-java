// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

public final class BusySpinIdleStrategy implements IdleStrategy {
    @Override
    public void idle(int workCount) {
        if (workCount <= 0) {
            Thread.onSpinWait();
        }
    }

    @Override
    public void reset() {
    }
}
