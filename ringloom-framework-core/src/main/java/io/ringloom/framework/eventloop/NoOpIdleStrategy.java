// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

/**
 * Idle strategy that performs no waiting or backoff.
 */
public final class NoOpIdleStrategy implements IdleStrategy {
    @Override
    public void idle(int workCount) {}

    @Override
    public void reset() {}
}
