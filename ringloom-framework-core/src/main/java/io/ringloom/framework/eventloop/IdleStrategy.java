// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

public interface IdleStrategy {
    void idle(int workCount);

    void reset();
}
