// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.generated;

import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.framework.dispatch.PartitionKeyExtractor;
import io.ringloom.service.RingloomMessage;

/**
 * SPI implemented by generated partition-key extractors.
 */
@FunctionalInterface
public interface GeneratedPartitionKeyExtractor extends PartitionKeyExtractor {
    /**
     * Computes the partition key used to select a worker for the supplied inbound message.
     *
     * @param message the low-level RingLoom message
     * @param context the prepared message context
     * @return the partition key
     */
    @Override
    long partitionKey(RingloomMessage message, MessageContext context);
}
