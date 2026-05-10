// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

public interface Agent {
    int doWork();

    default void onStart() {
    }

    default void onClose() {
    }

    default String name() {
        return getClass().getSimpleName();
    }
}
