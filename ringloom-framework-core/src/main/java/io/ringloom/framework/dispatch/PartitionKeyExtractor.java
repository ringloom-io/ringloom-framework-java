// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import io.ringloom.service.RingloomMessage;

@FunctionalInterface
public interface PartitionKeyExtractor {
    long partitionKey(RingloomMessage message, MessageContext ingressContext);
}
