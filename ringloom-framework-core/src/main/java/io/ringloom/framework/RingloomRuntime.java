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
import io.ringloom.framework.generated.GeneratedTopicPublisherBinding;
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
    private io.ringloom.framework.topic.TopicRuntime topicRuntime;

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
        if (config.topics().enabled()) {
            startTopics();
        }
        generatedApplication.onRuntimeStarted(this);
        if (config.runtime().mode() == RuntimeMode.EXTERNAL) {
            logStartupComplete();
        }
    }

    /**
     * Constructs the topic runtime, registers generated publishers and subscriptions, wires the topic
     * dispatcher into the execution policy, and starts the prefetcher.
     */
    private void startTopics() {
        topicRuntime = new io.ringloom.framework.topic.TopicRuntime(
                service,
                config.topics(),
                serializers,
                metrics,
                logger,
                config.runtime().mode());
        if (!generatedApplication.requiresTopicBindings()) {
            return;
        }
        // Register publishers and build ack registries.
        for (GeneratedTopicPublisherBinding binding : generatedApplication.topicPublishers()) {
            RingloomClient client = resolveTopicClient(binding.client());
            io.ringloom.service.TopicPublisher handle =
                    topicRuntime.registerPublication(client, binding.topic(), binding.topicConfig());
            long topicId = handle.topicId();
            io.ringloom.framework.topic.ack.TopicAckRegistry ackRegistry =
                    new io.ringloom.framework.topic.ack.TopicAckRegistry(1024, metrics, logger);
            topicRuntime.registerAckRegistry(binding.topic(), topicId, ackRegistry);
            Object generatedPublisher = binding.create(this, client, serializers, ackRegistry);
            generatedClients.put(binding.publisherType(), generatedPublisher);
        }
        // Register subscriptions for each handler.
        java.util.List<Long> resolvedTopicIds = new java.util.ArrayList<>();
        for (io.ringloom.framework.generated.GeneratedTopicHandlerBinding binding :
                generatedApplication.topicHandlers()) {
            RingloomClient client = resolveTopicClient("");
            io.ringloom.service.TopicStart start = resolveTopicStart(binding);
            io.ringloom.service.TopicSubscription subscription = topicRuntime.subscribe(client, binding.topic(), start);
            resolvedTopicIds.add(subscription.topicId());
        }
        if (!resolvedTopicIds.isEmpty()) {
            long[] ids = new long[resolvedTopicIds.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = resolvedTopicIds.get(i);
            }
            generatedApplication.initializeTopicIds(ids);
        }
        // Wire the topic dispatcher + source into the execution policy.
        io.ringloom.framework.generated.GeneratedTopicDispatcher topicDispatcher =
                generatedApplication.topicDispatcher();
        if (topicDispatcher != null) {
            io.ringloom.framework.topic.TopicMessageSource source =
                    new io.ringloom.framework.topic.TopicMessageSource(messageExecutionPolicy, topicDispatcher, this);
            topicRuntime.messageSource(source);
            wireTopicDispatcher(messageExecutionPolicy, topicDispatcher);
        }
        topicRuntime.start(Thread.ofPlatform().name("ringloom-topic-prefetcher").factory());
        // Drive periodic ack-timeout sweeps from the scheduler (on the control thread).
        topicRuntime.scheduleAckTimeoutSweep(scheduler);
    }

    private RingloomClient resolveTopicClient(String alias) {
        if (alias != null && !alias.isBlank() && lowLevelClients.containsKey(alias)) {
            return lowLevelClients.get(alias);
        }
        if (!lowLevelClients.isEmpty()) {
            return lowLevelClients.values().iterator().next();
        }
        // Fall back to a client for this service if none are registered under an alias.
        return service.createClient(config.service().name());
    }

    private io.ringloom.service.TopicStart resolveTopicStart(
            io.ringloom.framework.generated.GeneratedTopicHandlerBinding binding) {
        io.ringloom.service.TopicStart annotated = binding.start();
        io.ringloom.framework.config.topic.TopicHandlerConfig override =
                config.topics().handlers().get(binding.topic());
        if (override != null && override.start() != null) {
            return override.start();
        }
        return annotated == null ? io.ringloom.service.TopicStart.EARLIEST : annotated;
    }

    private void wireTopicDispatcher(
            MessageExecutionPolicy policy, io.ringloom.framework.generated.GeneratedTopicDispatcher topicDispatcher) {
        switch (policy) {
            case ConsumerThreadExecutionPolicy consumer -> consumer.topicDispatcher(topicDispatcher);
            case VirtualThreadExecutionPolicy virtual -> virtual.topicDispatcher(topicDispatcher);
            case PartitionedWorkerExecutionPolicy partitioned -> partitioned.topicDispatcher(topicDispatcher, null);
            default -> {}
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
        if (topicRuntime != null) {
            topicRuntime.pollAckFeedback();
        }
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
     * Advances every topic subscription and dispatches received messages through the wired source.
     *
     * <p>In {@code DEDICATED}/{@code SHARED} modes with {@code topics.coalesceWithMessages} enabled,
     * this is invoked automatically on the message thread. In {@code EXTERNAL} mode the caller must
     * invoke it (typically alongside {@link #pollControl()} and {@link #pollMessages()}).
     *
     * @return the number of topic messages dispatched
     */
    public int pollTopics() {
        ensureStarted();
        if (topicRuntime == null) {
            return 0;
        }
        return topicRuntime.pollTopics(config.runtime().messages().pollLimit());
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
        Runnable topicPollHook =
                (topicRuntime != null && config.topics().coalesceWithMessages()) ? this::pollTopics : null;
        MessageConsumerAgent messageAgent = new MessageConsumerAgent(
                "ringloom-message-consumer-agent",
                consumer,
                messageExecutionPolicy,
                this,
                config.runtime().messages().pollLimit(),
                topicPollHook);
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
     * Returns the persistent-topics runtime, or {@code null} when topics are not enabled.
     *
     * @return the topics runtime, or {@code null}
     */
    public io.ringloom.framework.topic.TopicRuntime topicRuntime() {
        return topicRuntime;
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
            closeQuietly(topicRuntime, "topic runtime");
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
