// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework;

import io.ringloom.framework.config.MessageExecutionMode;
import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.config.RuntimeMode;
import io.ringloom.framework.dispatch.ConsumerThreadExecutionPolicy;
import io.ringloom.framework.dispatch.MessageExecutionPolicy;
import io.ringloom.framework.dispatch.PartitionKeyExtractor;
import io.ringloom.framework.dispatch.PartitionedWorkerExecutionPolicy;
import io.ringloom.framework.dispatch.VirtualThreadExecutionPolicy;
import io.ringloom.framework.eventloop.ControlAgent;
import io.ringloom.framework.eventloop.CpuAffinity;
import io.ringloom.framework.eventloop.EventLoop;
import io.ringloom.framework.eventloop.IdleStrategies;
import io.ringloom.framework.eventloop.MessageConsumerAgent;
import io.ringloom.framework.eventloop.SchedulerAgent;
import io.ringloom.framework.generated.GeneratedClientBinding;
import io.ringloom.framework.generated.GeneratedRingloomApplication;
import io.ringloom.framework.metrics.RingloomMetrics;
import io.ringloom.framework.metrics.RuntimeRingloomMetrics;
import io.ringloom.framework.metrics.UnavailableRingloomMetrics;
import io.ringloom.framework.request.PooledRequestResponseRegistry;
import io.ringloom.framework.request.RequestResponseRegistry;
import io.ringloom.framework.scheduler.RingloomScheduler;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.framework.status.RingloomHandlerStatus;
import io.ringloom.framework.tracing.NoopTraceAdapter;
import io.ringloom.framework.tracing.TraceAdapter;
import io.ringloom.service.MessageConsumer;
import io.ringloom.service.MessageHandler;
import io.ringloom.service.RingloomClient;
import io.ringloom.service.RingloomMessage;
import io.ringloom.service.RingloomService;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agrona.concurrent.CompositeAgent;
import org.slf4j.Logger;

/**
 * Owns the running Java-side RingLoom runtime, including generated clients, event loops, and
 * request tracking.
 */
public final class RingloomRuntime implements AutoCloseable {

    private final RingloomApplicationConfig config;
    private final GeneratedRingloomApplication generatedApplication;
    private final SerializerRegistry serializers;
    private final RingloomMetrics metrics;
    private final TraceAdapter traceAdapter;
    private final boolean tracingEnabled;
    private final Logger logger;
    private final RequestResponseRegistry requestRegistry;
    private final RingloomScheduler scheduler;
    private final ThreadLocal<io.ringloom.framework.dispatch.MessageContext> pollContexts;
    private final MessageHandler pollMessageHandler = this::onPolledMessage;
    private final Map<String, RingloomClient> lowLevelClients = new HashMap<>();
    private final Map<Class<?>, Object> generatedClients = new HashMap<>();
    private final Object shutdownMonitor = new Object();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean startupLogged = new AtomicBoolean(false);
    private volatile long startupStartedNanos;
    private RingloomService service;
    private MessageConsumer consumer;
    private MessageExecutionPolicy messageExecutionPolicy;
    private EventLoop controlLoop;
    private EventLoop messageLoop;

    /**
     * Creates a runtime for a generated application and configuration.
     *
     * @param config the application configuration
     * @param generatedApplication the generated application metadata
     * @param serializers the serializer registry available to generated code
     * @param metrics the metrics facade to expose
     * @param logger the logger used by runtime components
     */
    public RingloomRuntime(
            RingloomApplicationConfig config,
            GeneratedRingloomApplication generatedApplication,
            SerializerRegistry serializers,
            RingloomMetrics metrics,
            Logger logger) {
        this(config, generatedApplication, serializers, metrics, NoopTraceAdapter.INSTANCE, logger);
    }

    /**
     * Creates a runtime for a generated application, configuration, and tracing adapter.
     *
     * @param config the application configuration
     * @param generatedApplication the generated application metadata
     * @param serializers the serializer registry available to generated code
     * @param metrics the metrics facade to expose
     * @param traceAdapter the tracing adapter used by generated clients and handlers
     * @param logger the logger used by runtime components
     */
    public RingloomRuntime(
            RingloomApplicationConfig config,
            GeneratedRingloomApplication generatedApplication,
            SerializerRegistry serializers,
            RingloomMetrics metrics,
            TraceAdapter traceAdapter,
            Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.generatedApplication = Objects.requireNonNull(generatedApplication, "generatedApplication");
        this.serializers = Objects.requireNonNull(serializers, "serializers");
        this.metrics = metrics == UnavailableRingloomMetrics.INSTANCE
                ? new RuntimeRingloomMetrics()
                : Objects.requireNonNull(metrics, "metrics");
        this.traceAdapter = Objects.requireNonNull(traceAdapter, "traceAdapter");
        this.tracingEnabled = traceAdapter != NoopTraceAdapter.INSTANCE;
        this.logger = Objects.requireNonNull(logger, "logger");
        if (config.runtime().tracing().enabled() && traceAdapter == NoopTraceAdapter.INSTANCE) {
            this.logger.warn("RingLoom tracing is configured but no TraceAdapter is installed");
        }
        this.requestRegistry =
                new PooledRequestResponseRegistry(config.runtime().requests().maxPending());
        this.scheduler = new RingloomScheduler(config.runtime().scheduler(), this);
        this.pollContexts = ThreadLocal.withInitial(() -> new io.ringloom.framework.dispatch.MessageContext(this));
    }

    /**
     * Starts the native RingLoom service, generated clients, and message execution policy.
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (generatedApplication.requiresCorrelationAwareSends()) {
            throw new IllegalStateException("generated application requires correlation-aware native send ABI");
        }
        startupStartedNanos = System.nanoTime();
        service = RingloomService.start(config.service().toLowLevelConfig());
        if (metrics instanceof RuntimeRingloomMetrics runtimeMetrics) {
            runtimeMetrics.attach(service);
        }
        consumer = service.messageConsumer();
        for (GeneratedClientBinding<?> binding : generatedApplication.clients()) {
            RingloomClient lowLevel = service.createClient(binding.targetServiceName());
            lowLevelClients.put(binding.targetServiceName(), lowLevel);
            Object generatedClient = binding.create(this, lowLevel, serializers);
            generatedClients.put(binding.clientType(), generatedClient);
        }
        generatedApplication.initializeSerializers(serializers);
        messageExecutionPolicy = createMessageExecutionPolicy();
        generatedApplication.onRuntimeStarted(this);
        if (config.runtime().mode() == RuntimeMode.EXTERNAL) {
            logStartupComplete();
        }
    }

    /**
     * Polls the native control channel once.
     *
     * @return the amount of control work performed
     */
    public int pollControl() {
        ensureStarted();
        int controlWork = service.pollControl(config.runtime().control().pollLimit());
        if (config.runtime().mode() != RuntimeMode.EXTERNAL) {
            return controlWork;
        }
        try {
            return controlWork + scheduler.poll(System.nanoTime());
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("scheduled control work failed", ex);
        }
    }

    /**
     * Polls inbound messages once.
     *
     * @return the amount of message work performed
     */
    public int pollMessages() {
        ensureStarted();
        return consumer.poll(pollMessageHandler, config.runtime().messages().pollLimit());
    }

    /**
     * Starts the configured event loops using the supplied thread factory.
     *
     * @param threadFactory the thread factory used to create event-loop threads
     */
    public void startEventLoops(ThreadFactory threadFactory) {
        ensureStarted();
        RuntimeMode mode = config.runtime().mode();
        if (mode == RuntimeMode.EXTERNAL) {
            logStartupComplete();
            return;
        }
        ControlAgent controlAgent =
                new ControlAgent(service, config.runtime().control().pollLimit());
        SchedulerAgent schedulerAgent = new SchedulerAgent(scheduler);
        MessageConsumerAgent messageAgent = new MessageConsumerAgent(
                consumer,
                messageExecutionPolicy,
                this,
                config.runtime().messages().pollLimit());
        if (mode == RuntimeMode.SHARED) {
            messageLoop = new EventLoop(
                    "ringloom-shared",
                    new CompositeAgent(controlAgent, schedulerAgent, messageAgent),
                    IdleStrategies.create(config.runtime().messages().idleStrategy()),
                    logger,
                    eventLoopAffinity(sharedCpuCore()));
            messageLoop.startThread(threadFactory);
            logStartupComplete();
            return;
        }
        controlLoop = new EventLoop(
                "ringloom-control",
                new CompositeAgent(controlAgent, schedulerAgent),
                IdleStrategies.create(config.runtime().control().idleStrategy()),
                logger,
                eventLoopAffinity(config.runtime().control().cpuCore()));
        messageLoop = new EventLoop(
                "ringloom-messages",
                messageAgent,
                IdleStrategies.create(config.runtime().messages().idleStrategy()),
                logger,
                eventLoopAffinity(config.runtime().messages().cpuCore()));
        controlLoop.startThread(threadFactory);
        messageLoop.startThread(threadFactory);
        logStartupComplete();
    }

    /**
     * Blocks until the runtime has fully shut down.
     *
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void awaitShutdown() throws InterruptedException {
        synchronized (shutdownMonitor) {
            while (!closed.get()) {
                shutdownMonitor.wait();
            }
        }
    }

    /**
     * Returns the low-level client for a generated target service.
     *
     * @param targetServiceName the target service name
     * @return the low-level client
     */
    public RingloomClient lowLevelClient(String targetServiceName) {
        ensureStarted();
        RingloomClient client = lowLevelClients.get(targetServiceName);
        if (client == null) {
            throw new IllegalArgumentException("unknown target service " + targetServiceName);
        }
        return client;
    }

    /**
     * Returns a generated client implementation by its public interface type.
     *
     * @param clientType the generated client interface type
     * @param <T> the generated client type
     * @return the generated client implementation
     */
    @SuppressWarnings("unchecked")
    public <T> T generatedClient(Class<T> clientType) {
        ensureStarted();
        Object client = generatedClients.get(clientType);
        if (client == null) {
            throw new IllegalArgumentException("unknown generated client " + clientType.getName());
        }
        return (T) client;
    }

    /**
     * Returns the active inbound message execution policy.
     *
     * @return the message execution policy
     */
    public MessageExecutionPolicy messageExecutionPolicy() {
        ensureStarted();
        return messageExecutionPolicy;
    }

    /**
     * Resolves a serializer name, falling back to the configured default serializer when the
     * generated API leaves the name blank.
     *
     * @param serializerName the generated serializer name override, possibly blank
     * @return the resolved serializer name, possibly blank when no default is configured
     */
    public String resolveSerializerName(String serializerName) {
        if (serializerName == null || serializerName.isBlank()) {
            return config.serializers().defaultSerializer();
        }
        return serializerName;
    }

    /**
     * Returns the request/response registry used by generated client code.
     *
     * @return the request/response registry
     */
    public RequestResponseRegistry requestResponseRegistry() {
        return requestRegistry;
    }

    /**
     * Returns the control-loop scheduler.
     *
     * @return the scheduler owned by this runtime
     */
    public RingloomScheduler scheduler() {
        ensureStarted();
        return scheduler;
    }

    /**
     * Returns the runtime metrics facade.
     *
     * @return the metrics facade
     */
    public RingloomMetrics metrics() {
        return metrics;
    }

    /**
     * Returns whether generated tracing hooks should call the trace adapter.
     *
     * @return {@code true} when tracing is enabled
     */
    public boolean tracingEnabled() {
        return tracingEnabled;
    }

    /**
     * Returns the trace adapter used by generated clients and handlers.
     *
     * @return the trace adapter
     */
    public TraceAdapter traceAdapter() {
        return traceAdapter;
    }

    /**
     * Stops the runtime and releases native resources.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (started.get()) {
                generatedApplication.onRuntimeStopping(this);
            }
            closeQuietly(messageLoop, "message event loop");
            closeQuietly(controlLoop, "control event loop");
            closeQuietly(messageExecutionPolicy, "message execution policy");
            requestRegistry.completeAll(RingloomHandlerStatus.SHUTDOWN);
            if (metrics instanceof RuntimeRingloomMetrics runtimeMetrics) {
                closeQuietly(runtimeMetrics, "metrics");
            }
            closeQuietly(consumer, "message consumer");
            for (RingloomClient client : lowLevelClients.values()) {
                closeQuietly(client, "client");
            }
            lowLevelClients.clear();
            generatedClients.clear();
            closeQuietly(service, "service");
        } finally {
            synchronized (shutdownMonitor) {
                shutdownMonitor.notifyAll();
            }
        }
    }

    private MessageExecutionPolicy createMessageExecutionPolicy() {
        MessageExecutionMode mode = config.runtime().execution().mode();
        return switch (mode) {
            case CONSUMER_THREAD ->
                new ConsumerThreadExecutionPolicy(generatedApplication.dispatcher(), requestRegistry);
            case PARTITIONED_WORKERS ->
                new PartitionedWorkerExecutionPolicy(
                        this,
                        generatedApplication.dispatcher(),
                        partitionExtractor(),
                        config.runtime().execution().partitioned(),
                        Thread.ofPlatform().name("ringloom-worker-", 0).factory(),
                        IdleStrategies.create(config.runtime().messages().idleStrategy()));
            case VIRTUAL_THREADS ->
                new VirtualThreadExecutionPolicy(
                        generatedApplication.dispatcher(),
                        config.runtime().execution().virtualThreads());
        };
    }

    private Integer sharedCpuCore() {
        Integer messageCpu = config.runtime().messages().cpuCore();
        return messageCpu == null ? config.runtime().control().cpuCore() : messageCpu;
    }

    private static Runnable eventLoopAffinity(Integer cpuCore) {
        return cpuCore == null ? null : () -> CpuAffinity.setCurrentThreadAffinity(cpuCore);
    }

    private void logStartupComplete() {
        if (!startupLogged.compareAndSet(false, true)) {
            return;
        }
        long startedAt = startupStartedNanos == 0 ? System.nanoTime() : startupStartedNanos;
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        logger.info("RingLoom runtime bootstrapped in {} ms", elapsedMillis);
    }

    private void onPolledMessage(RingloomMessage message) {
        messageExecutionPolicy.onMessage(message, pollContexts.get());
    }

    private PartitionKeyExtractor partitionExtractor() {
        if (!generatedApplication.hasPartitionKeyExtractors()) {
            throw new IllegalStateException("partitioned execution requires generated partition-key extractors");
        }
        return (message, context) -> generatedApplication.partitionKey(message.templateId(), message, context);
    }

    private void ensureStarted() {
        if (!started.get() || closed.get()) {
            throw new IllegalStateException("RingloomRuntime is not running");
        }
    }

    private void closeQuietly(AutoCloseable closeable, String resourceName) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ex) {
            logger.warn("failed to close RingLoom {}", resourceName, ex);
        }
    }
}
