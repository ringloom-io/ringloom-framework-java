// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.framework.config.kv.KvAcquireMode;
import io.ringloom.framework.config.kv.KvStoreConfigOverride;
import io.ringloom.framework.config.kv.KvStoreRuntimeConfig;
import io.ringloom.framework.config.kv.KvTtlClock;
import io.ringloom.framework.metrics.UnavailableRingloomMetrics;
import io.ringloom.framework.status.RingloomHandlerStatus;
import io.ringloom.framework.topic.ack.AckCallback;
import io.ringloom.framework.topic.ack.AckStatus;
import io.ringloom.service.KvWriter;
import io.ringloom.service.RingloomStatus;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

final class KvWriterStoreTest {
    private static final MemorySegment KEY = Arena.ofAuto().allocate(8);
    private static final MemorySegment VALUE = Arena.ofAuto().allocate(16);

    private final FakeKvWriterPort port = new FakeKvWriterPort();
    private final List<KvWriterStateEvent> events = new ArrayList<>();

    private KvWriterStore store(long ackTimeoutMillis, Consumer<KvWriterStateEvent> dispatcher) {
        KvStoreRuntimeConfig settings = new KvStoreRuntimeConfig(
                false,
                true,
                false,
                5000,
                false,
                KvTtlClock.PRECISE,
                64,
                4,
                KvAcquireMode.WAIT,
                5000,
                0,
                64,
                ackTimeoutMillis,
                KvStoreConfigOverride.none());
        KvWriterStore store = new KvWriterStore(
                "prices",
                FakeKvWriterPort.CONFIG,
                settings,
                UnavailableRingloomMetrics.INSTANCE,
                LoggerFactory.getLogger(KvWriterStoreTest.class),
                dispatcher);
        return store;
    }

    private KvWriterStore store() {
        return store(0, events::add);
    }

    @Test
    void transitionsDispatchInOrderAndUpdateState() {
        port.state = KvWriter.QUEUED;
        port.writerEpoch = 0;
        KvWriterStore store = store();
        store.attach(port);
        assertThat(store.state()).isEqualTo(KvWriterState.QUEUED);

        store.recordState(KvWriter.GRANTED, 7);
        store.recordState(KvWriter.REASSERTING, 7);
        store.recordState(KvWriter.GRANTED, 7);
        store.recordState(KvWriter.REVOKED, 0);
        assertThat(store.afterControlPoll()).isEqualTo(5);

        assertThat(events)
                .containsExactly(
                        new KvWriterStateEvent("prices", KvWriterState.QUEUED, null, 0),
                        new KvWriterStateEvent("prices", KvWriterState.GRANTED, KvWriterState.QUEUED, 7),
                        new KvWriterStateEvent("prices", KvWriterState.REASSERTING, KvWriterState.GRANTED, 7),
                        new KvWriterStateEvent("prices", KvWriterState.GRANTED, KvWriterState.REASSERTING, 7),
                        new KvWriterStateEvent("prices", KvWriterState.REVOKED, KvWriterState.GRANTED, 0));
        assertThat(store.state()).isEqualTo(KvWriterState.REVOKED);
        assertThat(store.writerEpoch()).isZero();
        assertThat(store.afterControlPoll()).as("drained ring is idle").isZero();
        assertThat(events).hasSize(5);
    }

    @Test
    void transitionsRecordedDuringOpenCollapseIntoInitialState() {
        KvWriterStore store = store();
        store.recordState(KvWriter.QUEUED, 0);
        store.recordState(KvWriter.GRANTED, 3);
        port.state = KvWriter.GRANTED;
        port.writerEpoch = 3;
        store.attach(port);
        store.recordState(KvWriter.GRANTED, 3); // duplicate of the initial state
        store.afterControlPoll();
        assertThat(events).containsExactly(new KvWriterStateEvent("prices", KvWriterState.GRANTED, null, 3));
    }

    @Test
    void grantedWithoutLeaseEpochIsReportedAsQueued() {
        KvWriterStore store = store();
        port.state = KvWriter.QUEUED;
        port.writerEpoch = 0;
        store.attach(port);
        store.recordState(KvWriter.GRANTED, 0); // observed natively for a standby; writes still fail
        store.recordState(KvWriter.GRANTED, 2);
        store.afterControlPoll();
        assertThat(events)
                .containsExactly(
                        new KvWriterStateEvent("prices", KvWriterState.QUEUED, null, 0),
                        new KvWriterStateEvent("prices", KvWriterState.GRANTED, KvWriterState.QUEUED, 2));
        assertThat(store.state()).isEqualTo(KvWriterState.GRANTED);
    }

    @Test
    void ringOverflowKeepsNewestState() {
        KvWriterStore store = store();
        store.attach(port);
        for (int i = 0; i < 40; i++) {
            store.recordState(i % 2 == 0 ? KvWriter.REASSERTING : KvWriter.GRANTED, 1);
        }
        store.recordState(KvWriter.REVOKED, 0);
        store.afterControlPoll();
        assertThat(events).hasSizeLessThanOrEqualTo(16);
        assertThat(events.getLast().state()).isEqualTo(KvWriterState.REVOKED);
        assertThat(store.state()).isEqualTo(KvWriterState.REVOKED);
    }

    @Test
    void throwingHandlerDoesNotStopDispatch() {
        AtomicInteger calls = new AtomicInteger();
        KvWriterStore store = store(0, event -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        });
        port.state = KvWriter.QUEUED;
        store.attach(port);
        store.recordState(KvWriter.GRANTED, 2);
        store.recordState(KvWriter.REVOKED, 0);
        store.afterControlPoll();
        assertThat(calls.get()).isEqualTo(3);
        assertThat(store.state()).isEqualTo(KvWriterState.REVOKED);
    }

    @Test
    void acksCompleteWhenAckedSequenceReachesSequence() {
        KvWriterStore store = store();
        store.attach(port);
        RecordingCallback callback = new RecordingCallback();
        assertThat(store.putAck(KEY, 0, 8, VALUE, 0, 16, KvTtl.STORE_DEFAULT, callback, "a"))
                .isEqualTo(RingloomStatus.OK);
        assertThat(store.putAck(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER, callback, "b"))
                .isEqualTo(RingloomStatus.OK);
        store.afterControlPoll();
        assertThat(callback.statuses).isEmpty();

        port.ackedSequence = 1;
        store.afterControlPoll();
        assertThat(callback.statuses).containsExactly(AckStatus.ACKED);
        assertThat(callback.contexts).containsExactly("a");

        port.ackedSequence = 2;
        store.afterControlPoll();
        assertThat(callback.statuses).containsExactly(AckStatus.ACKED, AckStatus.ACKED);
        assertThat(store.ackRegistry().pendingCount()).isZero();
    }

    @Test
    void ackRegisteredAfterAckedSequenceAlreadyCoveredItStillCompletes() {
        KvWriterStore store = store();
        store.attach(port);
        RecordingCallback callback = new RecordingCallback();
        store.putAck(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER, callback, null);
        port.ackedSequence = 2; // covers the next publish too
        store.afterControlPoll();
        store.putAck(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER, callback, null);
        store.afterControlPoll();
        assertThat(callback.statuses).containsExactly(AckStatus.ACKED, AckStatus.ACKED);
    }

    @Test
    void leaderEpochIncreaseCompletesOlderEntriesWithLeaderChanged() {
        KvWriterStore store = store();
        store.attach(port);
        RecordingCallback callback = new RecordingCallback();
        store.deleteAck(KEY, 0, 8, callback, "old");
        store.afterControlPoll();
        port.leaderEpoch = 2;
        store.clearAck(callback, "new");
        store.afterControlPoll();
        assertThat(callback.statuses).containsExactly(AckStatus.LEADER_CHANGED);
        assertThat(callback.contexts).containsExactly("old");
        port.ackedSequence = 2;
        store.afterControlPoll();
        assertThat(callback.statuses).containsExactly(AckStatus.LEADER_CHANGED, AckStatus.ACKED);
    }

    @Test
    void revokeCompletesEverythingWithRevokedAndKeepsTracking() {
        KvWriterStore store = store();
        store.attach(port);
        RecordingCallback callback = new RecordingCallback();
        store.putAck(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER, callback, null);
        store.putAck(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER, callback, null);
        store.recordState(KvWriter.REVOKED, 0);
        store.afterControlPoll();
        assertThat(callback.statuses).containsExactly(AckStatus.REVOKED, AckStatus.REVOKED);

        // A later grant can track acks again.
        store.recordState(KvWriter.GRANTED, 9);
        store.afterControlPoll();
        store.putAck(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER, callback, null);
        port.ackedSequence = 3;
        store.afterControlPoll();
        assertThat(callback.statuses).containsExactly(AckStatus.REVOKED, AckStatus.REVOKED, AckStatus.ACKED);
    }

    @Test
    void timeoutYieldsAckTimeout() {
        KvWriterStore store = store(5, events::add);
        store.attach(port);
        RecordingCallback callback = new RecordingCallback();
        store.putAck(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER, callback, null);
        assertThat(store.sweepAckTimeouts(System.nanoTime())).isZero();
        assertThat(store.sweepAckTimeouts(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(10)))
                .isEqualTo(1);
        assertThat(callback.statuses).containsExactly(AckStatus.ACK_TIMEOUT);
    }

    @Test
    void shutdownCompletesPendingAcksAndRejectsWrites() {
        KvWriterStore store = store();
        store.attach(port);
        RecordingCallback callback = new RecordingCallback();
        store.putAck(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER, callback, null);
        store.close(100);
        assertThat(callback.statuses).containsExactly(AckStatus.SHUTDOWN);
        assertThat(port.closed).isTrue();
        assertThat(store.state()).isEqualTo(KvWriterState.CLOSED);
        assertThat(store.put(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER)).isEqualTo(RingloomHandlerStatus.SHUTDOWN);
        assertThat(store.putAck(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER, callback, null))
                .isEqualTo(RingloomHandlerStatus.SHUTDOWN);
        assertThat(callback.statuses).hasSize(1);
    }

    @Test
    void nonOkWriteDoesNotRegisterAck() {
        KvWriterStore store = store();
        store.attach(port);
        port.nextStatus = RingloomStatus.KV_NOT_WRITER;
        RecordingCallback callback = new RecordingCallback();
        assertThat(store.putAck(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER, callback, null))
                .isEqualTo(RingloomStatus.KV_NOT_WRITER);
        assertThat(store.ackRegistry().pendingCount()).isZero();
    }

    @Test
    void writesBeforeOpenReturnNotReady() {
        KvWriterStore store = store();
        assertThat(store.put(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER)).isEqualTo(RingloomStatus.NOT_READY);
    }

    @Test
    void batchBlocksOtherThreadsUntilCommit() throws Exception {
        KvWriterStore store = store();
        store.attach(port);
        assertThat(store.beginBatch()).isEqualTo(RingloomStatus.OK);
        assertThat(store.put(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER)).isEqualTo(RingloomStatus.OK);

        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger otherStatus = new AtomicInteger(-1);
        Thread other = Thread.ofPlatform().start(() -> {
            started.countDown();
            otherStatus.set(store.put(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER));
        });
        started.await();
        Thread.sleep(50);
        assertThat(otherStatus.get()).as("other thread blocked during batch").isEqualTo(-1);
        assertThatThrownBy(() -> runOn(store::commitBatch)).hasCauseInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> store.putAck(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::beginBatch).isInstanceOf(IllegalStateException.class);

        RecordingCallback callback = new RecordingCallback();
        assertThat(store.commitBatchAck(callback, null)).isEqualTo(RingloomStatus.OK);
        other.join(2000);
        assertThat(otherStatus.get()).isEqualTo(RingloomStatus.OK);
        port.ackedSequence = port.lastSequence;
        store.afterControlPoll();
        assertThat(callback.statuses).containsExactly(AckStatus.ACKED);
    }

    @Test
    void failedCommitKeepsBatchAndLockUntilRetryOrAbandon() throws Exception {
        KvWriterStore store = store();
        store.attach(port);
        store.beginBatch();
        store.put(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER);
        port.nextStatus = RingloomStatus.BACKPRESSURE;
        assertThat(store.commitBatch()).isEqualTo(RingloomStatus.BACKPRESSURE);
        assertThat(port.inBatch).isTrue();
        port.nextStatus = RingloomStatus.OK;
        store.abandonBatch();
        assertThat(port.inBatch).isFalse();
        // Lock released: another thread can write.
        AtomicInteger status = new AtomicInteger(-1);
        Thread other = Thread.ofPlatform().start(() -> status.set(store.put(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER)));
        other.join(2000);
        assertThat(status.get()).isEqualTo(RingloomStatus.OK);
    }

    @Test
    void untrackedCommitBatchAckCallbackRunsAfterBatchIsClosed() {
        KvWriterStore store = store();
        store.attach(port);
        fillAckPool(store);

        RecordingCallback nested = new RecordingCallback();
        AtomicInteger nestedPutStatus = new AtomicInteger(-1);
        List<Boolean> portInBatchDuringCallback = new ArrayList<>();
        RecordingCallback callback = new RecordingCallback() {
            @Override
            public void onComplete(long publishIndex, AckStatus status, Object userContext) {
                super.onComplete(publishIndex, status, userContext);
                portInBatchDuringCallback.add(port.inBatch);
                store.abandonBatch(); // must be a no-op: the batch is already committed
                nestedPutStatus.set(store.putAck(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER, nested, null));
            }
        };

        assertThat(store.beginBatch()).isEqualTo(RingloomStatus.OK);
        store.put(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER);
        assertThat(store.commitBatchAck(callback, "ctx")).isEqualTo(RingloomStatus.OK);

        assertThat(callback.statuses).containsExactly(AckStatus.SHUTDOWN);
        assertThat(callback.contexts).containsExactly("ctx");
        assertThat(portInBatchDuringCallback).containsExactly(false);
        assertThat(nestedPutStatus.get()).isEqualTo(RingloomStatus.OK);
        assertThat(nested.statuses).containsExactly(AckStatus.SHUTDOWN);
        assertThat(store.beginBatch()).as("lock released exactly once").isEqualTo(RingloomStatus.OK);
        store.abandonBatch();
    }

    @Test
    void untrackedPutAckCallbackRunsWithoutWriterLock() {
        KvWriterStore store = store();
        store.attach(port);
        fillAckPool(store);

        AtomicInteger otherThreadStatus = new AtomicInteger(-1);
        AtomicInteger statusSeenInCallback = new AtomicInteger(-1);
        RecordingCallback callback = new RecordingCallback() {
            @Override
            public void onComplete(long publishIndex, AckStatus status, Object userContext) {
                super.onComplete(publishIndex, status, userContext);
                Thread other = Thread.ofPlatform()
                        .start(() -> otherThreadStatus.set(store.put(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER)));
                try {
                    other.join(500);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                statusSeenInCallback.set(otherThreadStatus.get());
            }
        };

        assertThat(store.putAck(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER, callback, null))
                .isEqualTo(RingloomStatus.OK);
        assertThat(callback.statuses).containsExactly(AckStatus.SHUTDOWN);
        assertThat(statusSeenInCallback.get())
                .as("writer lock was free during callback")
                .isEqualTo(RingloomStatus.OK);
    }

    private static void fillAckPool(KvWriterStore store) {
        RecordingCallback filler = new RecordingCallback();
        int capacity = store.ackRegistry().maxPending();
        for (int i = 0; i < capacity; i++) {
            assertThat(store.putAck(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER, filler, null))
                    .isEqualTo(RingloomStatus.OK);
        }
        assertThat(store.ackRegistry().pendingCount()).isEqualTo(capacity);
        assertThat(filler.statuses).isEmpty();
    }

    @Test
    void steadyStatePutAckRegistrationAndCompletionAllocateNothing() {
        KvWriterStore store = new KvWriterStore(
                "alloc",
                FakeKvWriterPort.CONFIG,
                KvStoreRuntimeConfig.defaults(),
                UnavailableRingloomMetrics.INSTANCE,
                null,
                null);
        store.attach(port);
        store.afterControlPoll();
        CountingCallback callback = new CountingCallback();
        for (int i = 0; i < 50_000; i++) {
            putAndAck(store, callback);
        }
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();
        long before = threads.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < 100_000; i++) {
            putAndAck(store, callback);
        }
        long allocated = threads.getThreadAllocatedBytes(threadId) - before;
        assertThat(callback.acked).isEqualTo(150_000);
        assertThat(allocated).isZero();
    }

    private void putAndAck(KvWriterStore store, CountingCallback callback) {
        int status = store.putAck(KEY, 0, 8, VALUE, 0, 16, KvTtl.NEVER, callback, null);
        if (status != RingloomStatus.OK) {
            throw new AssertionError("status " + status);
        }
        port.ackedSequence = port.lastSequence;
        store.afterControlPoll();
    }

    private static void runOn(java.util.function.IntSupplier action) throws Exception {
        Throwable[] failure = new Throwable[1];
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                action.getAsInt();
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        thread.join();
        if (failure[0] != null) {
            throw new Exception(failure[0]);
        }
    }

    private static class RecordingCallback implements AckCallback {
        final List<AckStatus> statuses = new ArrayList<>();
        final List<Object> contexts = new ArrayList<>();

        @Override
        public void onComplete(long publishIndex, AckStatus status, Object userContext) {
            statuses.add(status);
            contexts.add(userContext);
        }
    }

    private static final class CountingCallback implements AckCallback {
        long acked;

        @Override
        public void onComplete(long publishIndex, AckStatus status, Object userContext) {
            if (status == AckStatus.ACKED) {
                acked++;
            }
        }
    }
}
