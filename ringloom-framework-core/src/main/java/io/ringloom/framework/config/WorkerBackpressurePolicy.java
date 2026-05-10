// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

/**
 * Defines how the consumer thread reacts when a partitioned worker queue is full.
 */
public enum WorkerBackpressurePolicy {
    PARK_CONSUMER,
    FAIL_FAST
}
