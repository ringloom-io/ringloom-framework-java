// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.topic;

import io.ringloom.framework.config.RuntimeMode;
import io.ringloom.framework.config.topic.TopicsRuntimeConfig;
import io.ringloom.framework.eventloop.EventLoop;
import io.ringloom.framework.metrics.RingloomMetrics;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.framework.topic.ack.AckStatus;
import io.ringloom.framework.topic.ack.TopicAckRegistry;
import io.ringloom.service.RingloomClient;
import io.ringloom.service.RingloomService;
import io.ringloom.service.RingloomStatus;
import io.ringloom.service.TopicConfig;
import io.ringloom.service.TopicPublisher;
import io.ringloom.service.TopicStart;
import io.ringloom.service.TopicSubscription;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.slf4j.Logger;

/**
 * Owns the persistent-topic publishers and subscriptions for a {@link
 * io.ringloom.framework.RingloomRuntime}, drives the maintenance prefetcher, and exposes
 * {@link #pollTopics()} for message-thread (or external) polling.
 *
 * <p>All handles are populated at runtime startup and read lock-free afterwards; mutation happens
 * only on {@link #close()}. The prefetcher thread runs ringloom-queue {@code maintenancePoll} work
 * off the message poll path. Topic identity comes from the binding
 * ({@link TopicPublisher#topicId()} / {@link TopicSubscription#topicId()}) — the framework never
 * computes its own hash.
 */
public final class TopicRuntime implements AutoCloseable {
    private final RingloomService service;
    private final TopicsRuntimeConfig config;
    private final SerializerRegistry serializers;
    private final RingloomMetrics metrics;
    private final Logger logger;
    private final RuntimeMode runtimeMode;

    private final Map<String, TopicPublisher> publishers = new HashMap<>();
    private final Map<String, TopicSubscription> subscriptions = new HashMap<>();
    private final Map<Long, String> topicIdToName = new HashMap<>();
    private final Map<Long, TopicPublisher> publishersByTopicId = new HashMap<>();
    private final Map<Long, TopicAckRegistry> publisherRegistriesByTopicId = new HashMap<>();
    private final List<TopicPollState> pollStates = new ArrayList<>();

    private volatile TopicMessageSource topicMessageSource;
    private volatile TopicPollState[] pollStatesArray = EMPTY_POLL_STATES;
    private volatile EventLoop prefetcherLoop;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);

    private static final TopicPollState[] EMPTY_POLL_STATES = new TopicPollState[0];

    /**
     * Creates a new topic runtime.
     *
     * @param service      the native RingLoom service
     * @param config       the topics runtime configuration
     * @param serializers  the runtime serializer registry
     * @param metrics      the runtime metrics registry
     * @param logger       the runtime logger
     * @param runtimeMode  the runtime mode (controls prefetcher idle strategy and coalescing)
     */
    public TopicRuntime(
            RingloomService service,
            TopicsRuntimeConfig config,
            SerializerRegistry serializers,
            RingloomMetrics metrics,
            Logger logger,
            RuntimeMode runtimeMode) {
        this.service = service;
        this.config = config;
        this.serializers = serializers;
        this.metrics = metrics;
        this.logger = logger;
        this.runtimeMode = runtimeMode;
    }

    /**
     * Imperatively registers a topic publication and tracks the handle. Used by tests and the
     * generated publisher binding path.
     *
     * @param client    the low-level client to register through
     * @param topicName the topic name
     * @param topicCfg  the native topic configuration
     * @return the native publisher handle
     */
    public TopicPublisher registerPublication(RingloomClient client, String topicName, TopicConfig topicCfg) {
        ensureOpen();
        TopicPublisher publisher = client.registerTopicPublication(topicName, topicCfg);
        publishers.put(topicName, publisher);
        long topicId = publisher.topicId();
        if (topicId != 0L) {
            publishersByTopicId.put(topicId, publisher);
            topicIdToName.put(topicId, topicName);
        }
        return publisher;
    }

    /**
     * Imperatively subscribes to a topic and tracks the handle plus a preallocated poll state. Used
     * by tests and the generated handler binding path.
     *
     * @param client    the low-level client to subscribe through
     * @param topicName the topic name
     * @param start     the starting position
     * @return the native subscription handle
     */
    public TopicSubscription subscribe(RingloomClient client, String topicName, TopicStart start) {
        ensureOpen();
        TopicStart effectiveStart = start == null ? TopicStart.EARLIEST : start;
        TopicSubscription subscription = client.subscribeTopic(topicName, effectiveStart);
        subscriptions.put(topicName, subscription);
        long topicId = subscription.topicId();
        if (topicId != 0L) {
            topicIdToName.put(topicId, topicName);
        }
        pollStates.add(new TopicPollState(subscription, topicId, topicName));
        pollStatesArray = pollStates.toArray(EMPTY_POLL_STATES);
        return subscription;
    }

    /**
     * Associates a per-publisher ack registry. The generated publisher proxy owns the registry; the
     * runtime keeps a reference so the control-thread ack poll can drive it.
     *
     * @param topicName the topic name
     * @param topicId   the broker-assigned topic id
     * @param registry  the per-publisher ack registry
     */
    public void registerAckRegistry(String topicName, long topicId, TopicAckRegistry registry) {
        ensureOpen();
        publisherRegistriesByTopicId.put(topicId, registry);
    }

    /** Returns the ack registry for a topic id, or {@code null} when none is registered. */
    public TopicAckRegistry ackRegistry(long topicId) {
        return publisherRegistriesByTopicId.get(topicId);
    }

    /** Returns the publisher handle for a topic name, or {@code null}. */
    public TopicPublisher publisher(String topicName) {
        return publishers.get(topicName);
    }

    /** Returns the publisher handle for a topic id, or {@code null}. */
    public TopicPublisher publisherByTopicId(long topicId) {
        return publishersByTopicId.get(topicId);
    }

    /** Returns the topic name for a topic id, or {@code null}. */
    public String topicName(long topicId) {
        return topicIdToName.get(topicId);
    }

    /**
     * Wires the topic message source built by the runtime. {@code null} until phase 3 dispatch is
     * attached, in which case {@link #pollTopics()} only advances cursors and returns the count.
     *
     * @param source the topic message source, or {@code null} to detach
     */
    public void messageSource(TopicMessageSource source) {
        this.topicMessageSource = source;
    }

    /**
     * Advances every subscription and dispatches received messages through the wired source.
     *
     * <p>Zero per-poll allocation in steady state: the {@link TopicPollResult} and the
     * {@link TopicMessage}/{@link TopicContext} carried by the source are reused. Returns the number
     * of messages dispatched.
     *
     * @param perSubscriptionLimit the maximum messages to drain per subscription this tick
     * @return the number of messages dispatched
     */
    public int pollTopics(int perSubscriptionLimit) {
        if (closed.get()) {
            return 0;
        }
        TopicPollState[] states = pollStatesArray;
        if (states.length == 0) {
            return 0;
        }
        TopicMessageSource source = topicMessageSource;
        int perSubLimit = perSubscriptionLimit <= 0 ? Integer.MAX_VALUE : perSubscriptionLimit;
        int dispatched = 0;
        for (TopicPollState state : states) {
            if (source == null) {
                // Source not wired yet (phase 2 path): drain to keep the cursor advancing and count.
                int drained = 0;
                while (state.subscription.poll(state.result) == RingloomStatus.OK) {
                    drained++;
                    if (drained >= perSubLimit) break;
                }
                dispatched += drained;
                continue;
            }
            int subCount = 0;
            while (state.subscription.poll(state.result) == RingloomStatus.OK) {
                source.offer(state.topicName, state.topicId, state.result);
                dispatched++;
                subCount++;
                if (subCount >= perSubLimit) break;
            }
        }
        return dispatched;
    }

    /** Convenience overload using a default per-subscription limit. */
    public int pollTopics() {
        return pollTopics(0);
    }

    /**
     * Round-robin maintenance poll. Drives {@code maintenancePoll} on every subscription; used as the
     * prefetcher agent body.
     *
     * @return the number of work units processed
     */
    int runMaintenance() {
        TopicPollState[] states = pollStatesArray;
        if (states.length == 0) {
            return 0;
        }
        int workUnits = config.prefetcher().pollLimit();
        int perSub = Math.max(1, workUnits / states.length);
        int total = 0;
        for (TopicPollState state : states) {
            int status = state.subscription.maintenancePoll(perSub);
            if (status == RingloomStatus.OK) {
                total += perSub;
            }
        }
        return total;
    }

    /**
     * Polls leader-epoch and replicated-count feedback from every registered publisher and drives the
     * matching ack registry. Runs on the framework control thread each tick.
     */
    public void pollAckFeedback() {
        if (closed.get() || publishersByTopicId.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, TopicPublisher> entry : publishersByTopicId.entrySet()) {
            TopicPublisher publisher = entry.getValue();
            TopicAckRegistry registry = publisherRegistriesByTopicId.get(entry.getKey());
            if (registry == null) {
                continue;
            }
            long epoch = publisher.leaderEpoch();
            long hwm = publisher.replicatedCount();
            long knownEpoch = registry.knownEpoch();
            registry.advanceHwm(epoch, hwm);
            if (epoch > knownEpoch && epoch > 0) {
                registry.onLeaderChanged(epoch);
            }
        }
    }

    /**
     * Schedules a periodic sweep of ack-registry timeouts on the supplied scheduler. The sweep runs on
     * the control thread (via {@link io.ringloom.framework.scheduler.RingloomScheduledTask}) and completes
     * past-deadline entries with {@link AckStatus#ACK_TIMEOUT}.
     *
     * @param scheduler the runtime scheduler (may be {@code null}; then no sweep is scheduled)
     */
    public void scheduleAckTimeoutSweep(io.ringloom.framework.scheduler.RingloomScheduler scheduler) {
        if (scheduler == null || publisherRegistriesByTopicId.isEmpty()) {
            return;
        }
        scheduler.scheduleAtFixedRate(0, 100, java.util.concurrent.TimeUnit.MILLISECONDS, runtime -> {
            long now = System.nanoTime();
            for (TopicAckRegistry registry : publisherRegistriesByTopicId.values()) {
                registry.sweepTimeouts(now);
            }
        });
    }

    /**
     * Starts the maintenance prefetcher thread. No-op for {@link RuntimeMode#EXTERNAL} when the caller
     * drives maintenance itself, unless explicitly requested.
     *
     * @param threadFactory the thread factory used to start the prefetcher event loop
     */
    public void start(java.util.concurrent.ThreadFactory threadFactory) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (pollStates.isEmpty()) {
            return;
        }
        if (runtimeMode == RuntimeMode.EXTERNAL) {
            // The caller drives polling in EXTERNAL mode; skip the prefetcher thread.
            return;
        }
        Agent agent = new PrefetcherAgent();
        IdleStrategy idle = createPrefetcherIdleStrategy();
        EventLoop loop = new EventLoop("ringloom-topic-prefetcher", agent, idle, logger, prefetcherInitializer());
        this.prefetcherLoop = loop;
        loop.startThread(threadFactory);
    }

    private Runnable prefetcherInitializer() {
        Integer cpuAffinity = config.prefetcher().cpuAffinity();
        if (cpuAffinity == null) {
            return null;
        }
        return () -> {
            try {
                io.ringloom.framework.eventloop.CpuAffinity.setCurrentThreadAffinity(cpuAffinity);
            } catch (Throwable ex) {
                if (logger != null) {
                    logger.warn("Could not pin topic prefetcher to CPU {}", cpuAffinity, ex);
                }
            }
        };
    }

    private IdleStrategy createPrefetcherIdleStrategy() {
        long intervalMicros = config.prefetcher().intervalMicros();
        if (intervalMicros == 0L) {
            return BusySpinIdleStrategy.INSTANCE;
        }
        // No dedicated parkNanos idle strategy in IdleStrategies; reuse backoff for parking.
        return new BackoffIdleStrategy(100, 10, Math.max(1L, intervalMicros * 1_000L), intervalMicros * 1_000L);
    }

    /** Closes all handles and stops the prefetcher thread. Idempotent; runs ack shutdown first. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (TopicAckRegistry registry : publisherRegistriesByTopicId.values()) {
            try {
                registry.completeAll(AckStatus.SHUTDOWN);
            } catch (Throwable ex) {
                if (logger != null) {
                    logger.warn("Topic ack registry shutdown failed", ex);
                }
            }
        }
        if (prefetcherLoop != null) {
            closeQuietly(prefetcherLoop, "topic prefetcher loop");
            prefetcherLoop = null;
        }
        for (TopicSubscription subscription : subscriptions.values()) {
            closeQuietly(subscription, "topic subscription");
        }
        for (TopicPublisher publisher : publishers.values()) {
            closeQuietly(publisher, "topic publisher");
        }
        subscriptions.clear();
        publishers.clear();
        publishersByTopicId.clear();
        publisherRegistriesByTopicId.clear();
        topicIdToName.clear();
        pollStates.clear();
        pollStatesArray = EMPTY_POLL_STATES;
    }

    /** Returns whether topics are enabled and this runtime has not been closed. */
    public boolean active() {
        return !closed.get();
    }

    /** Returns whether this runtime has any registered subscriptions. */
    public boolean hasSubscriptions() {
        return !pollStates.isEmpty();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("TopicRuntime is closed");
        }
    }

    private void closeQuietly(AutoCloseable closeable, String description) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ex) {
            if (logger != null) {
                logger.warn("Failed to close {}", description, ex);
            }
        }
    }

    /** Agrona agent that drives the maintenance prefetcher. */
    private final class PrefetcherAgent implements Agent {
        @Override
        public int doWork() {
            return runMaintenance();
        }

        @Override
        public String roleName() {
            return "ringloom-topic-prefetcher";
        }
    }
}
