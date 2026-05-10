// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

public final class NoOpIdleStrategy implements IdleStrategy {
    @Override
    public void idle(int workCount) {
    }

    @Override
    public void reset() {
    }
}
