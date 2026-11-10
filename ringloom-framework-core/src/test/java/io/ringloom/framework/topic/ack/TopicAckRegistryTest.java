// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.topic.ack;

import static org.assertj.core.api.Assertions.assertThat;

import io.ringloom.framework.metrics.UnavailableRingloomMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class TopicAckRegistryTest {

    private record Completion(long publishIndex, AckStatus status, Object userContext) {}

    private static final AckCallback RECORDING_CALLBACK = (publishIndex, status, userContext) -> {
        @SuppressWarnings("unchecked")
        List<Completion> sink = (List<Completion>) userContext;
        sink.add(new Completion(publishIndex, status, null));
    };

    private final TopicAckRegistry registry = new TopicAckRegistry(8, UnavailableRingloomMetrics.INSTANCE, null);

    @Test
    void advanceReplicatedCountCompletesAckedPrefix() {
        List<Completion> sink = new ArrayList<>();

        assertThat(registry.register(10, 1L, RECORDING_CALLBACK, sink, 0L)).isTrue();
        assertThat(registry.register(11, 1L, RECORDING_CALLBACK, sink, 0L)).isTrue();
        assertThat(registry.register(12, 1L, RECORDING_CALLBACK, sink, 0L)).isTrue();
        assertThat(registry.pendingCount()).isEqualTo(3);

        registry.advanceReplicatedCount(1L, 11L);

        assertThat(sink)
                .containsExactlyInAnyOrder(
                        new Completion(10L, AckStatus.ACKED, null), new Completion(11L, AckStatus.ACKED, null));
        assertThat(registry.pendingCount()).isEqualTo(1);
    }

    @Test
    void advanceReplicatedCountIgnoresStaleEpochs() {
        List<Completion> sink = new ArrayList<>();
        registry.register(20, 2L, RECORDING_CALLBACK, sink, 0L);

        registry.advanceReplicatedCount(1L, 30L);

        assertThat(sink).isEmpty();
        assertThat(registry.pendingCount()).isEqualTo(1);
    }

    @Test
    void onLeaderChangedCompletesStaleEpochEntries() {
        List<Completion> sink = new ArrayList<>();
        registry.register(30, 1L, RECORDING_CALLBACK, sink, 0L);
        registry.register(31, 2L, RECORDING_CALLBACK, sink, 0L);

        registry.onLeaderChanged(3L);

        assertThat(sink)
                .containsExactlyInAnyOrder(
                        new Completion(30L, AckStatus.LEADER_CHANGED, null),
                        new Completion(31L, AckStatus.LEADER_CHANGED, null));
        assertThat(registry.pendingCount()).isZero();
        assertThat(registry.knownEpoch()).isEqualTo(3L);
    }

    @Test
    void sweepTimeoutsCompletesOnlyPastDeadline() {
        List<Completion> sink = new ArrayList<>();
        long now = System.nanoTime();
        registry.register(40, 1L, RECORDING_CALLBACK, sink, now + 1_000_000L);
        registry.register(41, 1L, RECORDING_CALLBACK, sink, now - 1L);

        int swept = registry.sweepTimeouts(now);

        assertThat(swept).isEqualTo(1);
        assertThat(sink).containsExactly(new Completion(41L, AckStatus.ACK_TIMEOUT, null));
        assertThat(registry.pendingCount()).isEqualTo(1);
    }

    @Test
    void completeAllReleasesEverythingAndBlocksFurtherRegistration() {
        List<Completion> sink = new ArrayList<>();
        registry.register(50, 1L, RECORDING_CALLBACK, sink, 0L);
        registry.register(51, 1L, RECORDING_CALLBACK, sink, 0L);

        registry.completeAll(AckStatus.SHUTDOWN);

        assertThat(sink).hasSize(2);
        assertThat(sink).allMatch(completion -> completion.status() == AckStatus.SHUTDOWN);
        assertThat(registry.register(52, 1L, RECORDING_CALLBACK, sink, 0L)).isFalse();
    }

    @Test
    void pooledEntriesAreReusedAfterCompletion() {
        List<Completion> sink = new ArrayList<>();

        for (int i = 0; i < 8; i++) {
            registry.register(60 + i, 1L, RECORDING_CALLBACK, sink, 0L);
        }
        assertThat(registry.register(68, 1L, RECORDING_CALLBACK, sink, 0L))
                .as("pool exhausted at capacity")
                .isFalse();

        registry.advanceReplicatedCount(1L, 67L);
        // The 8 pooled entries are back in the free queue.
        assertThat(registry.register(68, 1L, RECORDING_CALLBACK, sink, 0L)).isTrue();
    }

    @Test
    void callbacksRunOutsideRegistryMonitor() throws Exception {
        // A callback that waits for another thread to register (e.g. a KV writer holding its batch lock
        // while registering) must not deadlock: callbacks are dispatched after the monitor is released.
        List<Boolean> registeredFromOtherThread = new ArrayList<>();
        AckCallback callback = (publishIndex, status, userContext) -> {
            CompletableFuture<Boolean> other =
                    CompletableFuture.supplyAsync(() -> registry.register(71, 1L, null, null, 0L));
            try {
                registeredFromOtherThread.add(other.get(5, TimeUnit.SECONDS));
            } catch (Exception ex) {
                throw new AssertionError("register blocked while a callback was running", ex);
            }
        };
        registry.register(70, 1L, callback, null, 0L);

        registry.advanceReplicatedCount(1L, 70L);

        assertThat(registeredFromOtherThread).containsExactly(true);
        assertThat(registry.pendingCount()).isEqualTo(1);
    }

    @Test
    void callbackMayRegisterAndCompleteReentrantly() {
        List<Completion> sink = new ArrayList<>();
        AckCallback chaining = (publishIndex, status, userContext) -> {
            sink.add(new Completion(publishIndex, status, null));
            if (publishIndex == 80) {
                registry.register(81, 1L, RECORDING_CALLBACK, sink, 0L);
                registry.advanceReplicatedCount(1L, 81L);
            }
        };
        registry.register(80, 1L, chaining, null, 0L);

        registry.advanceReplicatedCount(1L, 80L);

        assertThat(sink)
                .containsExactly(
                        new Completion(80L, AckStatus.ACKED, null), new Completion(81L, AckStatus.ACKED, null));
        assertThat(registry.pendingCount()).isZero();
    }
}
