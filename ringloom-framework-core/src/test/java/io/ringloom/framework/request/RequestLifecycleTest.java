// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.framework.status.RingloomHandlerStatus;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class RequestLifecycleTest {
    @Test
    void requestTimeoutRequiresPositiveDuration() {
        // Given / When / Then
        assertThat(RequestTimeout.ofMillis(10).duration()).isEqualTo(Duration.ofMillis(10));
        assertThatThrownBy(() -> RequestTimeout.ofMillis(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duration must be positive");
        assertThatThrownBy(() -> new RequestTimeout(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duration must be positive");
    }

    @Test
    void responseCallbackDefaultsReturnFrameworkStatuses() {
        // Given
        ResponseCallback<String> callback = (response, context, userContext) -> RingloomHandlerStatus.OK;

        // When / Then
        assertThat(callback.onTimeout("user")).isEqualTo(RingloomHandlerStatus.OK);
        assertThat(callback.onFailure(RingloomHandlerStatus.REQUEST_CANCELLED, "user"))
                .isEqualTo(RingloomHandlerStatus.REQUEST_CANCELLED);
    }

    @Test
    void requestAwaiterCompletesAndTimesOut() throws Exception {
        // Given
        RequestAwaiter completed = new RequestAwaiter();
        completed.prepare(Thread.currentThread());
        completed.complete();
        RequestAwaiter timedOut = new RequestAwaiter();
        timedOut.prepare(Thread.currentThread());

        // When / Then
        assertThat(completed.awaitNanos(1)).isTrue();
        assertThat(timedOut.awaitNanos(1)).isFalse();
    }

    @Test
    void requestAwaiterPropagatesInterrupts() {
        // Given
        RequestAwaiter awaiter = new RequestAwaiter();
        awaiter.prepare(Thread.currentThread());

        // When / Then
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> awaiter.awaitNanos(1_000_000L)).isInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void pendingRequestPrepareAndCompletionResetState() {
        // Given
        RequestAwaiter awaiter = new RequestAwaiter();
        PendingRequest request = new PendingRequest(3);
        ResponseCallback<String> callback = (response, context, userContext) -> RingloomHandlerStatus.OK;

        // When
        request.prepare(42L, 99, callback, "ctx", 123L, awaiter);
        request.markRegistered();
        request.complete(RingloomHandlerStatus.OK);

        // Then
        assertThat(request.slot()).isEqualTo(3);
        assertThat(request.correlationId()).isEqualTo(42L);
        assertThat(request.expectedResponseTemplateId()).isEqualTo(99);
        assertThat(request.callback()).isSameAs(callback);
        assertThat(request.userContext()).isEqualTo("ctx");
        assertThat(request.deadlineNanos()).isEqualTo(123L);
        assertThat(request.completionStatus()).isEqualTo(RingloomHandlerStatus.OK);
        assertThat(request.registered()).isFalse();

        // When
        request.clear();

        // Then
        assertThat(request.generation()).isEqualTo(1);
        assertThat(request.correlationId()).isZero();
        assertThat(request.expectedResponseTemplateId()).isEqualTo(-1);
        assertThat(request.callback()).isNull();
        assertThat(request.userContext()).isNull();
        assertThat(request.awaiter()).isNull();
    }

    @Test
    void pooledRegistryHandlesCapacityDuplicateAndTemplateMismatch() {
        // Given
        PooledRequestResponseRegistry registry = new PooledRequestResponseRegistry(1);
        PendingRequest request = registry.acquire();
        request.prepare(request.correlationId(), 7, null, null, 0, null);

        // When / Then
        assertThat(registry.acquire()).isNull();
        assertThat(registry.register(request)).isEqualTo(RingloomHandlerStatus.OK);
        assertThat(registry.register(request)).isEqualTo(RingloomHandlerStatus.REQUEST_CANCELLED);
        assertThat(registry.resolve(request.correlationId(), 8)).isNull();
        assertThat(registry.resolve(request.correlationId(), 7)).isSameAs(request);

        // When
        registry.complete(request, RingloomHandlerStatus.OK);

        // Then
        assertThat(registry.acquire()).isNotNull();
    }

    @Test
    void pooledRegistryCompleteAllReleasesRegisteredRequests() {
        // Given
        PooledRequestResponseRegistry registry = new PooledRequestResponseRegistry(2);
        PendingRequest first = registry.acquire();
        first.prepare(first.correlationId(), 1, null, null, 0, null);
        PendingRequest second = registry.acquire();
        second.prepare(second.correlationId(), 2, null, null, 0, null);
        assertThat(registry.register(first)).isEqualTo(RingloomHandlerStatus.OK);
        assertThat(registry.register(second)).isEqualTo(RingloomHandlerStatus.OK);

        // When
        registry.completeAll(RingloomHandlerStatus.SHUTDOWN);

        // Then
        assertThat(first.completionStatus()).isZero();
        assertThat(second.completionStatus()).isZero();
        assertThat(registry.acquire()).isNotNull();
        assertThat(registry.acquire()).isNotNull();
        assertThat(registry.acquire()).isNull();
    }
}
