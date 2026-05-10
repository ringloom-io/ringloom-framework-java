// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.request;

import static org.assertj.core.api.Assertions.assertThat;

import io.ringloom.framework.status.RingloomHandlerStatus;
import org.junit.jupiter.api.Test;

final class PooledRequestResponseRegistryTest {
    @Test
    void staleCorrelationDoesNotResolveReusedSlot() {
        // Given
        PooledRequestResponseRegistry registry = new PooledRequestResponseRegistry(1);
        PendingRequest first = registry.acquire();
        long staleCorrelation = first.correlationId();
        first.prepare(staleCorrelation, 7, null, null, 0, null);
        assertThat(registry.register(first)).isEqualTo(RingloomHandlerStatus.OK);

        registry.cancel(first, RingloomHandlerStatus.REQUEST_TIMEOUT);

        PendingRequest second = registry.acquire();
        long currentCorrelation = second.correlationId();
        second.prepare(currentCorrelation, 7, null, null, 0, null);
        assertThat(registry.register(second)).isEqualTo(RingloomHandlerStatus.OK);

        // When
        PendingRequest resolved = registry.resolve(currentCorrelation, 7);

        // Then
        assertThat(staleCorrelation).isNotEqualTo(currentCorrelation);
        assertThat(registry.resolve(staleCorrelation, 7)).isNull();
        assertThat(resolved).isSameAs(second);
        assertThat(resolved.correlationId()).isEqualTo(currentCorrelation);
        registry.complete(resolved, RingloomHandlerStatus.OK);
    }
}
