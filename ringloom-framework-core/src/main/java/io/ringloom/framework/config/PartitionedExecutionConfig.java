// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import java.util.Objects;

public record PartitionedExecutionConfig(
    int workers,
    int queueCapacity,
    int maxPayloadBytes,
    WorkerBackpressurePolicy backpressure
) {
    public PartitionedExecutionConfig {
        workers = workers == 0 ? 1 : workers;
        queueCapacity = queueCapacity == 0 ? 1024 : queueCapacity;
        maxPayloadBytes = maxPayloadBytes == 0 ? 4096 : maxPayloadBytes;
        backpressure = Objects.requireNonNullElse(backpressure, WorkerBackpressurePolicy.PARK_CONSUMER);
        if (workers <= 0) {
            throw new IllegalArgumentException("partitioned.workers must be positive");
        }
        if (queueCapacity <= 0 || (queueCapacity & (queueCapacity - 1)) != 0) {
            throw new IllegalArgumentException("partitioned.queueCapacity must be a positive power of two");
        }
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("partitioned.maxPayloadBytes must be positive");
        }
    }

    public static PartitionedExecutionConfig defaults() {
        return new PartitionedExecutionConfig(1, 1024, 4096, WorkerBackpressurePolicy.PARK_CONSUMER);
    }
}
