// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import java.util.Objects;

public record MessageExecutionConfig(
    MessageExecutionMode mode,
    PartitionedExecutionConfig partitioned,
    VirtualThreadExecutionConfig virtualThreads
) {
    public MessageExecutionConfig {
        mode = Objects.requireNonNullElse(mode, MessageExecutionMode.CONSUMER_THREAD);
        partitioned = partitioned == null ? PartitionedExecutionConfig.defaults() : partitioned;
        virtualThreads = virtualThreads == null ? VirtualThreadExecutionConfig.defaults() : virtualThreads;
    }

    public static MessageExecutionConfig consumerThread() {
        return new MessageExecutionConfig(MessageExecutionMode.CONSUMER_THREAD, null, null);
    }
}
