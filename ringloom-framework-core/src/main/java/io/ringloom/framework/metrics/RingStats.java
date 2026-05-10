// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.metrics;

/**
 * Snapshot of occupancy and cursor positions for a RingLoom ring buffer.
 *
 * @param capacityBytes the ring capacity in bytes
 * @param usedBytes the bytes currently occupied
 * @param freeBytes the bytes currently free
 * @param producerPosition the producer cursor position
 * @param consumerPosition the consumer cursor position
 */
public record RingStats(
        long capacityBytes, long usedBytes, long freeBytes, long producerPosition, long consumerPosition) {}
