// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.config.PartitionedExecutionConfig;
import io.ringloom.framework.config.WorkerBackpressurePolicy;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.service.RingloomMessage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.agrona.BitUtil;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RecordDescriptor;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;

/**
 * Message execution policy that copies payloads to partition-affine worker threads.
 *
 * <p>Each worker uses a single-producer/single-consumer ring buffer. Runtime integrations must
 * preserve one ingress producer per worker, which the built-in event-loop modes do by polling
 * messages from one consumer thread.
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
        this(null, dispatcher, extractor, config, threadFactory, idleStrategy);
    }

    public PartitionedWorkerExecutionPolicy(
            RingloomRuntime runtime,
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
            Worker worker =
                    new Worker(runtime, dispatcher, config.queueCapacity(), config.maxPayloadBytes(), idleStrategy);
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
        if (closed.get()) {
            return -1;
        }
        ingressContext.updateFrom(message);
        long key = extractor.partitionKey(message, ingressContext);
        Worker worker = workers[Math.floorMod(Long.hashCode(key), workers.length)];
        while (!closed.get()) {
            if (worker.write(message, ingressContext.runtime())) {
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
        private static final int MESSAGE_TYPE_ID = 1;
        private static final int CORRELATION_ID_OFFSET = 0;
        private static final int SOURCE_NODE_ID_OFFSET = 8;
        private static final int SOURCE_SERVICE_ID_OFFSET = 10;
        private static final int TARGET_NODE_ID_OFFSET = 12;
        private static final int TARGET_SERVICE_ID_OFFSET = 14;
        private static final int TEMPLATE_ID_OFFSET = 16;
        private static final int FLAGS_OFFSET = 20;
        private static final int PAYLOAD_LENGTH_OFFSET = 24;
        private static final int PAYLOAD_OFFSET = 32;
        private static final int READ_LIMIT = 64;

        private final GeneratedMessageDispatcher dispatcher;
        private final int maxPayloadBytes;
        private final MessageContext context;
        private final IdleStrategy idleStrategy;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Arena arena = Arena.ofShared();
        private final MemorySegment ringSegment;
        private final OneToOneRingBuffer ringBuffer;
        private final AtomicBuffer atomicBuffer;
        private volatile RingloomRuntime runtime;
        private volatile Thread thread;

        Worker(
                RingloomRuntime runtime,
                GeneratedMessageDispatcher dispatcher,
                int queueCapacity,
                int maxPayloadBytes,
                IdleStrategy idleStrategy) {
            this.runtime = runtime;
            this.dispatcher = dispatcher;
            this.context = new MessageContext(runtime);
            this.maxPayloadBytes = maxPayloadBytes;
            this.idleStrategy = idleStrategy == null ? NoOpIdleStrategy.INSTANCE : idleStrategy;
            int dataCapacity = ringDataCapacity(queueCapacity, maxPayloadBytes);
            this.ringSegment =
                    arena.allocate(dataCapacity + RingBufferDescriptor.TRAILER_LENGTH, UnsafeBuffer.ALIGNMENT);
            this.atomicBuffer = new UnsafeBuffer(ringSegment.address(), Math.toIntExact(ringSegment.byteSize()));
            this.ringBuffer = new OneToOneRingBuffer(atomicBuffer);
        }

        boolean write(RingloomMessage message, RingloomRuntime ingressRuntime) {
            if (runtime == null && ingressRuntime != null) {
                runtime = ingressRuntime;
            }
            long payloadLength = message.payloadLength();
            if (payloadLength > maxPayloadBytes) {
                throw new IllegalArgumentException("payload exceeds partitioned worker maxPayloadBytes");
            }
            int payloadBytes = Math.toIntExact(payloadLength);
            int recordLength = PAYLOAD_OFFSET + payloadBytes;
            int index = ringBuffer.tryClaim(MESSAGE_TYPE_ID, recordLength);
            if (index == RingBuffer.INSUFFICIENT_CAPACITY) {
                return false;
            }
            try {
                writeMetadata(message, index, payloadBytes);
                MemorySegment.copy(
                        message.payloadSegment(),
                        ValueLayout.JAVA_BYTE,
                        0,
                        ringSegment,
                        ValueLayout.JAVA_BYTE,
                        index + PAYLOAD_OFFSET,
                        payloadBytes);
                ringBuffer.commit(index);
                return true;
            } catch (RuntimeException | Error ex) {
                ringBuffer.abort(index);
                throw ex;
            }
        }

        @Override
        public void run() {
            thread = Thread.currentThread();
            while (running.get() || ringBuffer.size() > 0) {
                int messagesRead = ringBuffer.read(this::dispatch, READ_LIMIT);
                idleStrategy.idle(messagesRead);
            }
        }

        private void writeMetadata(RingloomMessage message, int index, int payloadBytes) {
            atomicBuffer.putLong(index + CORRELATION_ID_OFFSET, message.correlationId());
            atomicBuffer.putShort(index + SOURCE_NODE_ID_OFFSET, message.sourceNodeId());
            atomicBuffer.putShort(index + SOURCE_SERVICE_ID_OFFSET, message.sourceServiceId());
            atomicBuffer.putShort(index + TARGET_NODE_ID_OFFSET, message.targetNodeId());
            atomicBuffer.putShort(index + TARGET_SERVICE_ID_OFFSET, message.targetServiceId());
            atomicBuffer.putInt(index + TEMPLATE_ID_OFFSET, message.templateId());
            atomicBuffer.putInt(index + FLAGS_OFFSET, message.flags());
            atomicBuffer.putInt(index + PAYLOAD_LENGTH_OFFSET, payloadBytes);
        }

        private void dispatch(int messageTypeId, DirectBuffer buffer, int index, int length) {
            if (messageTypeId != MESSAGE_TYPE_ID) {
                throw new IllegalStateException("unknown partitioned worker message type " + messageTypeId);
            }
            int payloadLength = buffer.getInt(index + PAYLOAD_LENGTH_OFFSET);
            if (length != PAYLOAD_OFFSET + payloadLength) {
                throw new IllegalStateException("corrupt partitioned worker record length");
            }
            MemorySegment payload = ringSegment.asSlice(index + PAYLOAD_OFFSET, payloadLength);
            context.updateCopied(
                    buffer.getLong(index + CORRELATION_ID_OFFSET),
                    buffer.getShort(index + SOURCE_NODE_ID_OFFSET),
                    buffer.getShort(index + SOURCE_SERVICE_ID_OFFSET),
                    buffer.getShort(index + TARGET_NODE_ID_OFFSET),
                    buffer.getShort(index + TARGET_SERVICE_ID_OFFSET),
                    buffer.getInt(index + TEMPLATE_ID_OFFSET),
                    buffer.getInt(index + FLAGS_OFFSET),
                    payload);
            context.runtime(runtime);
            dispatcher.onContextMessage(context);
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

        private static int ringDataCapacity(int queueCapacity, int maxPayloadBytes) {
            int maxRecordLength = PAYLOAD_OFFSET + maxPayloadBytes;
            long alignedRecordLength =
                    BitUtil.align(maxRecordLength + RecordDescriptor.HEADER_LENGTH, RecordDescriptor.ALIGNMENT);
            long recordsCapacity = Math.addExact(
                    Math.multiplyExact((long) queueCapacity, alignedRecordLength), RecordDescriptor.HEADER_LENGTH);
            long maxMessageCapacity = Math.multiplyExact((long) maxRecordLength, 8L);
            long requiredCapacity = Math.max(recordsCapacity, maxMessageCapacity);
            if (requiredCapacity > (long) Integer.MAX_VALUE - RingBufferDescriptor.TRAILER_LENGTH) {
                throw new IllegalArgumentException("partitioned worker ring buffer capacity exceeds integer range");
            }
            return nextPowerOfTwo(Math.toIntExact(requiredCapacity));
        }

        private static int nextPowerOfTwo(int value) {
            if (value <= 1) {
                return 1;
            }
            if (value > (1 << 30)) {
                throw new IllegalArgumentException(
                        "partitioned worker ring buffer capacity exceeds maximum power of two");
            }
            return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(value - 1));
        }
    }
}
