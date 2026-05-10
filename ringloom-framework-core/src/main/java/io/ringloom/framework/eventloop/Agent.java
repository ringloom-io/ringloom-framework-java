// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

/**
 * Unit of work executed by an {@link EventLoop}.
 */
public interface Agent {
    int doWork();

    default void onStart() {}

    default void onClose() {}

    default String name() {
        return getClass().getSimpleName();
    }
}
