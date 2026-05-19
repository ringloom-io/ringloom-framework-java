// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.config.PartitionedExecutionConfig;
import io.ringloom.framework.config.WorkerBackpressurePolicy;
import io.ringloom.framework.eventloop.IdleStrategy;
import io.ringloom.framework.eventloop.NoOpIdleStrategy;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.service.RingloomMessage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.jctools.queues.MpscArrayQueue;

/**
 * Message execution policy that copies payloads to partition-affine worker threads.
 */
public final class PartitionedWorkerExecutionPolicy implements MessageExecutionPolicy {
    private final PartitionKeyExtractor extractor;
    private final Worker[] workers;
    private final WorkerBackpressurePolicy backpressurePolicy;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public PartitionedWorkerExecutionPolicy(
            GeneratedMessageDispatcher dispatcher,
            PartitionKeyExtractor extractor,
            PartitionedExecutionConfig config,
            ThreadFactory threadFactory,
            IdleStrategy idleStrategy) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(threadFactory, "threadFactory");
        this.backpressurePolicy = config.backpressure();
        this.workers = new Worker[config.workers()];
        for (int i = 0; i < workers.length; i++) {
            Worker worker = new Worker(dispatcher, config.queueCapacity(), config.maxPayloadBytes(), idleStrategy);
            workers[i] = worker;
            Thread thread = threadFactory.newThread(worker);
            if (thread == null) {
                throw new IllegalStateException("thread factory returned null");
            }
            thread.start();
        }
    }

    @Override
    public int onMessage(RingloomMessage message, MessageContext ingressContext) {
        ingressContext.updateFrom(message);
        long key = extractor.partitionKey(message, ingressContext);
        Worker worker = workers[Math.floorMod(Long.hashCode(key), workers.length)];
        Slot slot = worker.copy(
                message, ingressContext.runtime(), backpressurePolicy != WorkerBackpressurePolicy.FAIL_FAST);
        if (slot == null) {
            return -1;
        }
        while (!closed.get()) {
            if (worker.offer(slot)) {
                return 1;
            }
            if (backpressurePolicy == WorkerBackpressurePolicy.FAIL_FAST) {
                worker.release(slot);
                return -1;
            }
            LockSupport.parkNanos(1_000L);
        }
        worker.release(slot);
        return -1;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Worker worker : workers) {
            worker.close();
        }
    }

    private static final class Worker implements Runnable, AutoCloseable {
        private final GeneratedMessageDispatcher dispatcher;
        private final MpscArrayQueue<Slot> free;
        private final MpscArrayQueue<Slot> queue;
        private final int maxPayloadBytes;
        private final MessageContext context = new MessageContext();
        private final IdleStrategy idleStrategy;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Arena arena = Arena.ofShared();
        private volatile Thread thread;

        Worker(
                GeneratedMessageDispatcher dispatcher,
                int queueCapacity,
                int maxPayloadBytes,
                IdleStrategy idleStrategy) {
            this.dispatcher = dispatcher;
            this.free = new MpscArrayQueue<>(queueCapacity);
            this.queue = new MpscArrayQueue<>(queueCapacity);
            this.maxPayloadBytes = maxPayloadBytes;
            this.idleStrategy = idleStrategy == null ? new NoOpIdleStrategy() : idleStrategy;
            for (int i = 0; i < queueCapacity; i++) {
                if (!free.offer(new Slot(arena.allocate(maxPayloadBytes), maxPayloadBytes))) {
                    throw new IllegalStateException("failed to initialize partitioned worker slot pool");
                }
            }
        }

        Slot copy(RingloomMessage message, RingloomRuntime runtime, boolean waitForSlot) {
            if (message.payloadLength() > maxPayloadBytes) {
                throw new IllegalArgumentException("payload exceeds partitioned worker maxPayloadBytes");
            }
            Slot slot = free.poll();
            while (slot == null && waitForSlot && running.get()) {
                LockSupport.parkNanos(1_000L);
                slot = free.poll();
            }
            if (slot == null) {
                return null;
            }
            long payloadLength = message.payloadLength();
            MemorySegment.copy(
                    message.payloadSegment(),
                    ValueLayout.JAVA_BYTE,
                    0,
                    slot.payload,
                    ValueLayout.JAVA_BYTE,
                    0,
                    payloadLength);
            slot.update(
                    runtime,
                    message.correlationId(),
                    message.sourceNodeId(),
                    message.sourceServiceId(),
                    message.targetNodeId(),
                    message.targetServiceId(),
                    message.templateId(),
                    message.flags(),
                    payloadLength);
            return slot;
        }

        boolean offer(Slot slot) {
            return slot != null && queue.offer(slot);
        }

        void release(Slot slot) {
            if (slot != null) {
                slot.clear();
                while (!free.offer(slot) && running.get()) {
                    LockSupport.parkNanos(1_000L);
                }
            }
        }

        @Override
        public void run() {
            thread = Thread.currentThread();
            while (running.get()) {
                Slot slot = queue.poll();
                if (slot == null) {
                    idleStrategy.idle(0);
                    continue;
                }
                try {
                    context.updateCopied(
                            slot.correlationId,
                            slot.sourceNodeId,
                            slot.sourceServiceId,
                            slot.targetNodeId,
                            slot.targetServiceId,
                            slot.templateId,
                            slot.flags,
                            slot.payloadSegment());
                    context.runtime(slot.runtime);
                    dispatcher.onContextMessage(context);
                    idleStrategy.idle(1);
                } finally {
                    release(slot);
                }
            }
        }

        @Override
        public void close() {
            running.set(false);
            Thread workerThread = thread;
            if (workerThread != null) {
                workerThread.interrupt();
                try {
                    workerThread.join();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            arena.close();
        }
    }

    private static final class Slot {
        private final MemorySegment payload;
        private final long maxPayloadBytes;
        private RingloomRuntime runtime;
        private long correlationId;
        private short sourceNodeId;
        private short sourceServiceId;
        private short targetNodeId;
        private short targetServiceId;
        private int templateId;
        private int flags;
        private long payloadLength;
        private MemorySegment payloadView;

        Slot(MemorySegment payload, long maxPayloadBytes) {
            this.payload = payload;
            this.maxPayloadBytes = maxPayloadBytes;
            this.payloadView = payload;
        }

        void update(
                RingloomRuntime runtime,
                long correlationId,
                short sourceNodeId,
                short sourceServiceId,
                short targetNodeId,
                short targetServiceId,
                int templateId,
                int flags,
                long payloadLength) {
            this.runtime = runtime;
            this.correlationId = correlationId;
            this.sourceNodeId = sourceNodeId;
            this.sourceServiceId = sourceServiceId;
            this.targetNodeId = targetNodeId;
            this.targetServiceId = targetServiceId;
            this.templateId = templateId;
            this.flags = flags;
            this.payloadLength = payloadLength;
            this.payloadView = payloadLength == maxPayloadBytes ? payload : payload.asSlice(0, payloadLength);
        }

        MemorySegment payloadSegment() {
            return payloadView;
        }

        void clear() {
            runtime = null;
            correlationId = 0;
            sourceNodeId = 0;
            sourceServiceId = 0;
            targetNodeId = 0;
            targetServiceId = 0;
            templateId = 0;
            flags = 0;
            payloadLength = 0;
            payloadView = payload;
        }
    }
}
