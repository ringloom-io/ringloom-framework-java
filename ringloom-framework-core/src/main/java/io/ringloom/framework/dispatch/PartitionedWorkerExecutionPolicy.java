// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.config.PartitionedExecutionConfig;
import io.ringloom.framework.config.WorkerBackpressurePolicy;
import io.ringloom.framework.eventloop.IdleStrategy;
import io.ringloom.framework.eventloop.NoOpIdleStrategy;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.service.RingloomMessage;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

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
        Slot slot = worker.copy(message, ingressContext.runtime());
        while (!closed.get()) {
            if (worker.offer(slot)) {
                return 1;
            }
            if (backpressurePolicy == WorkerBackpressurePolicy.FAIL_FAST) {
                return -1;
            }
            LockSupport.parkNanos(1_000L);
        }
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
        private final ArrayBlockingQueue<Slot> queue;
        private final int maxPayloadBytes;
        private final MessageContext context = new MessageContext();
        private final IdleStrategy idleStrategy;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile Thread thread;

        Worker(
                GeneratedMessageDispatcher dispatcher,
                int queueCapacity,
                int maxPayloadBytes,
                IdleStrategy idleStrategy) {
            this.dispatcher = dispatcher;
            this.queue = new ArrayBlockingQueue<>(queueCapacity);
            this.maxPayloadBytes = maxPayloadBytes;
            this.idleStrategy = idleStrategy == null ? new NoOpIdleStrategy() : idleStrategy;
        }

        Slot copy(RingloomMessage message, RingloomRuntime runtime) {
            if (message.payloadLength() > maxPayloadBytes) {
                throw new IllegalArgumentException("payload exceeds partitioned worker maxPayloadBytes");
            }
            byte[] payload = new byte[(int) message.payloadLength()];
            MemorySegment.copy(message.payloadSegment(), ValueLayout.JAVA_BYTE, 0, payload, 0, payload.length);
            return new Slot(
                    runtime,
                    message.correlationId(),
                    message.sourceNodeId(),
                    message.sourceServiceId(),
                    message.targetNodeId(),
                    message.targetServiceId(),
                    message.templateId(),
                    message.flags(),
                    payload);
        }

        boolean offer(Slot slot) {
            return queue.offer(slot);
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
                context.updateCopied(
                        slot.correlationId,
                        slot.sourceNodeId,
                        slot.sourceServiceId,
                        slot.targetNodeId,
                        slot.targetServiceId,
                        slot.templateId,
                        slot.flags,
                        MemorySegment.ofArray(slot.payload));
                context.runtime(slot.runtime);
                dispatcher.onContextMessage(context);
                idleStrategy.idle(1);
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
        }
    }

    private record Slot(
            RingloomRuntime runtime,
            long correlationId,
            short sourceNodeId,
            short sourceServiceId,
            short targetNodeId,
            short targetServiceId,
            int templateId,
            int flags,
            byte[] payload) {}
}
