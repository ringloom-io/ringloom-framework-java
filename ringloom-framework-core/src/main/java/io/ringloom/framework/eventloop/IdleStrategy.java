// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

/**
 * Strategy used by an {@link EventLoop} when an iteration performs little or no work.
 */
public interface IdleStrategy {
    void idle(int workCount);

    void reset();
}
