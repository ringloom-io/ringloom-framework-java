// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import io.ringloom.service.RingloomMessage;

/**
 * Extracts the partition key used for partitioned inbound message execution.
 */
@FunctionalInterface
public interface PartitionKeyExtractor {
    long partitionKey(RingloomMessage message, MessageContext ingressContext);
}
