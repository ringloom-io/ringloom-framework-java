// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.request;

import io.ringloom.framework.status.RingloomHandlerStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class PooledRequestResponseRegistryTest {
    @Test
    void staleCorrelationDoesNotResolveReusedSlot() {
        PooledRequestResponseRegistry registry = new PooledRequestResponseRegistry(1);
        PendingRequest first = registry.acquire();
        long staleCorrelation = first.correlationId();
        first.prepare(staleCorrelation, 7, null, null, 0, null);
        assertEquals(RingloomHandlerStatus.OK, registry.register(first));

        registry.cancel(first, RingloomHandlerStatus.REQUEST_TIMEOUT);

        PendingRequest second = registry.acquire();
        long currentCorrelation = second.correlationId();
        second.prepare(currentCorrelation, 7, null, null, 0, null);
        assertEquals(RingloomHandlerStatus.OK, registry.register(second));

        assertNotEquals(staleCorrelation, currentCorrelation);
        assertNull(registry.resolve(staleCorrelation, 7));
        PendingRequest resolved = registry.resolve(currentCorrelation, 7);
        assertSame(second, resolved);
        assertEquals(currentCorrelation, resolved.correlationId());
        registry.complete(resolved, RingloomHandlerStatus.OK);
    }
}
