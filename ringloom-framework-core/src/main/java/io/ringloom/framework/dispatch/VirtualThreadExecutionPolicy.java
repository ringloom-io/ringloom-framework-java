// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.dispatch;

import io.ringloom.framework.config.VirtualThreadExecutionConfig;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.service.RingloomMessage;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VirtualThreadExecutionPolicy implements MessageExecutionPolicy {
    private final GeneratedMessageDispatcher dispatcher;
    private final ExecutorService executor;
    private final Semaphore inFlight;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public VirtualThreadExecutionPolicy(GeneratedMessageDispatcher dispatcher, VirtualThreadExecutionConfig config) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.inFlight = new Semaphore(Objects.requireNonNull(config, "config").maxInFlight());
    }

    @Override
    public int onMessage(RingloomMessage message, MessageContext ingressContext) {
        if (closed.get()) {
            return -1;
        }
        ingressContext.updateFrom(message);
        byte[] payload = message.payloadSegment().toArray(ValueLayout.JAVA_BYTE);
        CopiedMessage copy = new CopiedMessage(
            message.correlationId(),
            message.sourceNodeId(),
            message.sourceServiceId(),
            message.targetNodeId(),
            message.targetServiceId(),
            message.templateId(),
            message.flags(),
            payload
        );
        try {
            inFlight.acquire();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return -1;
        }
        executor.execute(() -> {
            try {
                MessageContext context = new MessageContext();
                context.updateCopied(
                    copy.correlationId,
                    copy.sourceNodeId,
                    copy.sourceServiceId,
                    copy.targetNodeId,
                    copy.targetServiceId,
                    copy.templateId,
                    copy.flags,
                    MemorySegment.ofArray(copy.payload)
                );
                dispatcher.onContextMessage(context);
            } finally {
                inFlight.release();
            }
        });
        return 1;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private record CopiedMessage(
        long correlationId,
        short sourceNodeId,
        short sourceServiceId,
        short targetNodeId,
        short targetServiceId,
        int templateId,
        int flags,
        byte[] payload
    ) {
    }
}
