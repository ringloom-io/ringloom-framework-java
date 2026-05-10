// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.generated;

import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.framework.dispatch.PartitionKeyExtractor;
import io.ringloom.service.RingloomMessage;

@FunctionalInterface
public interface GeneratedPartitionKeyExtractor extends PartitionKeyExtractor {
    @Override
    long partitionKey(RingloomMessage message, MessageContext context);
}
