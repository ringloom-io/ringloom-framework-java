// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import java.util.Objects;

/**
 * Configures how inbound messages are dispatched after they are polled from the native consumer.
 *
 * @param mode the dispatch strategy to use
 * @param partitioned options for partitioned worker execution
 * @param virtualThreads options for virtual-thread execution
 */
public record MessageExecutionConfig(
        MessageExecutionMode mode,
        PartitionedExecutionConfig partitioned,
        VirtualThreadExecutionConfig virtualThreads) {
    public MessageExecutionConfig {
        mode = Objects.requireNonNullElse(mode, MessageExecutionMode.CONSUMER_THREAD);
        partitioned = partitioned == null ? PartitionedExecutionConfig.defaults() : partitioned;
        virtualThreads = virtualThreads == null ? VirtualThreadExecutionConfig.defaults() : virtualThreads;
    }

    /**
     * Creates the default configuration that keeps message handling on the consumer thread.
     *
     * @return the default consumer-thread execution configuration
     */
    public static MessageExecutionConfig consumerThread() {
        return new MessageExecutionConfig(MessageExecutionMode.CONSUMER_THREAD, null, null);
    }
}
