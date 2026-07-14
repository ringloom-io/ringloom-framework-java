// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.yaml;

import io.ringloom.framework.annotation.RoutingMode;
import io.ringloom.framework.config.IdleStrategyKind;
import io.ringloom.framework.config.MessageExecutionConfig;
import io.ringloom.framework.config.MessageExecutionMode;
import io.ringloom.framework.config.PartitionedExecutionConfig;
import io.ringloom.framework.config.RequestRuntimeConfig;
import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.config.RingloomClientRuntimeConfig;
import io.ringloom.framework.config.RingloomConfigLoader;
import io.ringloom.framework.config.RingloomEventLoopConfig;
import io.ringloom.framework.config.RingloomRuntimeConfig;
import io.ringloom.framework.config.RingloomSerializerConfig;
import io.ringloom.framework.config.RingloomServiceRuntimeConfig;
import io.ringloom.framework.config.RuntimeMode;
import io.ringloom.framework.config.SchedulerRuntimeConfig;
import io.ringloom.framework.config.TracingPropagationMode;
import io.ringloom.framework.config.TracingRuntimeConfig;
import io.ringloom.framework.config.TracingSamplerKind;
import io.ringloom.framework.config.VirtualThreadExecutionConfig;
import io.ringloom.framework.config.WorkerBackpressurePolicy;
import io.ringloom.framework.config.topic.TopicHandlerConfig;
import io.ringloom.framework.config.topic.TopicPrefetcherConfig;
import io.ringloom.framework.config.topic.TopicPublisherDefaults;
import io.ringloom.framework.config.topic.TopicsRuntimeConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * Strict YAML-backed {@link io.ringloom.framework.config.RingloomConfigLoader} implementation.
 */
public final class YamlRingloomConfigLoader implements RingloomConfigLoader {
    @Override
    public boolean supports(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".yaml") || fileName.endsWith(".yml");
    }

    @Override
    public RingloomApplicationConfig load(Path path) throws IOException {
        Object loaded = new Load(LoadSettings.builder().build()).loadFromString(Files.readString(path));
        Map<String, Object> root = map(loaded, "root");
        requireKeys(root, "root", Set.of("ringloom"));
        Map<String, Object> ringloom = map(root.get("ringloom"), "ringloom");
        requireKeys(ringloom, "ringloom", Set.of("service", "runtime", "serializers", "clients", "topics"));
        RingloomServiceRuntimeConfig service =
                service(map(required(ringloom, "service", "ringloom.service"), "ringloom.service"));
        RingloomRuntimeConfig runtime = runtime(optionalMap(ringloom.get("runtime"), "ringloom.runtime"));
        RingloomSerializerConfig serializers =
                serializers(optionalMap(ringloom.get("serializers"), "ringloom.serializers"));
        Map<String, RingloomClientRuntimeConfig> clients =
                clients(optionalMap(ringloom.get("clients"), "ringloom.clients"));
        TopicsRuntimeConfig topics = topics(optionalMap(ringloom.get("topics"), "ringloom.topics"));
        return new RingloomApplicationConfig(service, runtime, serializers, clients, topics);
    }

    private static TopicsRuntimeConfig topics(Map<String, Object> values) {
        if (values.isEmpty()) {
            return TopicsRuntimeConfig.disabled();
        }
        requireKeys(
                values,
                "ringloom.topics",
                Set.of("enabled", "coalesceWithMessages", "prefetcher", "publisherDefaults", "handlers"));
        boolean enabled = bool(values.get("enabled"), false);
        boolean coalesce = bool(values.get("coalesceWithMessages"), true);
        TopicPrefetcherConfig prefetcher =
                prefetcher(optionalMap(values.get("prefetcher"), "ringloom.topics.prefetcher"));
        TopicPublisherDefaults defaults =
                publisherDefaults(optionalMap(values.get("publisherDefaults"), "ringloom.topics.publisherDefaults"));
        Map<String, TopicHandlerConfig> handlers =
                topicHandlers(optionalMap(values.get("handlers"), "ringloom.topics.handlers"));
        return new TopicsRuntimeConfig(enabled, coalesce, prefetcher, defaults, handlers);
    }

    private static TopicPrefetcherConfig prefetcher(Map<String, Object> values) {
        if (values.isEmpty()) {
            return TopicPrefetcherConfig.defaults();
        }
        requireKeys(values, "ringloom.topics.prefetcher", Set.of("cpuAffinity", "pollLimit", "intervalMicros"));
        Integer cpuAffinity = optionalInteger(values.get("cpuAffinity"), "ringloom.topics.prefetcher.cpuAffinity");
        int pollLimit = integer(values.get("pollLimit"), "ringloom.topics.prefetcher.pollLimit", 64);
        long intervalMicros = longValue(values.get("intervalMicros"), "ringloom.topics.prefetcher.intervalMicros", 0L);
        return new TopicPrefetcherConfig(cpuAffinity, pollLimit, intervalMicros);
    }

    private static TopicPublisherDefaults publisherDefaults(Map<String, Object> values) {
        if (values.isEmpty()) {
            return TopicPublisherDefaults.defaults();
        }
        requireKeys(values, "ringloom.topics.publisherDefaults", Set.of("rollScheme", "retentionCycles"));
        String rollScheme = string(values.get("rollScheme"), "ringloom.topics.publisherDefaults.rollScheme");
        if (rollScheme == null || rollScheme.isBlank()) {
            rollScheme = "FAST_DAILY";
        }
        int retentionCycles =
                integer(values.get("retentionCycles"), "ringloom.topics.publisherDefaults.retentionCycles", 0);
        return new TopicPublisherDefaults(rollScheme, retentionCycles, 0);
    }

    private static Map<String, TopicHandlerConfig> topicHandlers(Map<String, Object> values) {
        if (values.isEmpty()) {
            return Map.of();
        }
        Map<String, TopicHandlerConfig> handlers = new HashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> handlerMap = map(entry.getValue(), "ringloom.topics.handlers." + name);
            requireKeys(
                    handlerMap,
                    "ringloom.topics.handlers." + name,
                    Set.of("topic", "start", "serializer", "partitionKey"));
            String topic = string(handlerMap.get("topic"), "ringloom.topics.handlers." + name + ".topic");
            io.ringloom.service.TopicStart start = enumValue(
                    io.ringloom.service.TopicStart.class,
                    string(handlerMap.get("start"), ""),
                    io.ringloom.service.TopicStart.EARLIEST);
            String serializer =
                    string(handlerMap.get("serializer"), "ringloom.topics.handlers." + name + ".serializer");
            String partitionKey =
                    string(handlerMap.get("partitionKey"), "ringloom.topics.handlers." + name + ".partitionKey");
            handlers.put(name, new TopicHandlerConfig(topic, start, serializer, partitionKey));
        }
        return handlers;
    }

    private static RingloomServiceRuntimeConfig service(Map<String, Object> values) {
        requireKeys(
                values,
                "ringloom.service",
                Set.of(
                        "name",
                        "storagePath",
                        "group",
                        "brokerNodeId",
                        "blockingMode",
                        "controlBufferLength",
                        "messagesBufferLength",
                        "heartbeatTimeoutMillis",
                        "leaderElectionEnabled"));
        return new RingloomServiceRuntimeConfig(
                string(required(values, "name", "ringloom.service.name"), "ringloom.service.name"),
                string(values.get("storagePath"), "ringloom.service.storagePath"),
                string(values.get("group"), "ringloom.service.group"),
                (short) integer(values.get("brokerNodeId"), "ringloom.service.brokerNodeId", 0),
                bool(values.get("blockingMode"), false),
                integer(values.get("heartbeatTimeoutMillis"), "ringloom.service.heartbeatTimeoutMillis", 0),
                longValue(values.get("controlBufferLength"), "ringloom.service.controlBufferLength", 0),
                longValue(values.get("messagesBufferLength"), "ringloom.service.messagesBufferLength", 0),
                bool(values.get("leaderElectionEnabled"), false));
    }

    private static RingloomRuntimeConfig runtime(Map<String, Object> values) {
        if (values.isEmpty()) {
            return RingloomRuntimeConfig.defaults();
        }
        requireKeys(
                values,
                "ringloom.runtime",
                Set.of("mode", "control", "messages", "scheduler", "requests", "lifecycle", "tracing"));
        RuntimeMode mode = enumValue(
                RuntimeMode.class, string(values.get("mode"), "ringloom.runtime.mode"), RuntimeMode.DEDICATED);
        RingloomEventLoopConfig control = eventLoop(optionalMap(values.get("control"), "ringloom.runtime.control"));
        Map<String, Object> messages = optionalMap(values.get("messages"), "ringloom.runtime.messages");
        RingloomEventLoopConfig messageLoop = eventLoop(messages);
        MessageExecutionConfig execution =
                execution(optionalMap(messages.get("execution"), "ringloom.runtime.messages.execution"));
        SchedulerRuntimeConfig scheduler =
                scheduler(optionalMap(values.get("scheduler"), "ringloom.runtime.scheduler"));
        RequestRuntimeConfig requests = requests(optionalMap(values.get("requests"), "ringloom.runtime.requests"));
        boolean shutdownHook = bool(
                optionalMap(values.get("lifecycle"), "ringloom.runtime.lifecycle")
                        .get("shutdownHook"),
                true);
        TracingRuntimeConfig tracing = tracing(optionalMap(values.get("tracing"), "ringloom.runtime.tracing"));
        return new RingloomRuntimeConfig(
                mode, control, messageLoop, execution, scheduler, requests, shutdownHook, tracing);
    }

    private static RingloomEventLoopConfig eventLoop(Map<String, Object> values) {
        if (values.isEmpty()) {
            return RingloomEventLoopConfig.defaults();
        }
        requireKeys(values, "eventLoop", Set.of("idleStrategy", "pollLimit", "cpuCore", "execution"));
        IdleStrategyKind idle = idleKind(string(values.get("idleStrategy"), "idleStrategy"));
        int pollLimit = integer(values.get("pollLimit"), "pollLimit", RingloomEventLoopConfig.DEFAULT_POLL_LIMIT);
        Integer cpuCore = optionalInteger(values.get("cpuCore"), "cpuCore");
        return new RingloomEventLoopConfig(idle, pollLimit, cpuCore);
    }

    private static MessageExecutionConfig execution(Map<String, Object> values) {
        if (values.isEmpty()) {
            return MessageExecutionConfig.consumerThread();
        }
        requireKeys(values, "ringloom.runtime.messages.execution", Set.of("mode", "partitioned", "virtualThreads"));
        MessageExecutionMode mode =
                executionMode(string(values.get("mode"), "ringloom.runtime.messages.execution.mode"));
        PartitionedExecutionConfig partitioned = partitioned(optionalMap(values.get("partitioned"), "partitioned"));
        VirtualThreadExecutionConfig virtualThreads = new VirtualThreadExecutionConfig(integer(
                optionalMap(values.get("virtualThreads"), "virtualThreads").get("maxInFlight"), "maxInFlight", 10_000));
        return new MessageExecutionConfig(mode, partitioned, virtualThreads);
    }

    private static PartitionedExecutionConfig partitioned(Map<String, Object> values) {
        return new PartitionedExecutionConfig(
                integer(values.get("workers"), "workers", 1),
                integer(values.get("queueCapacity"), "queueCapacity", 1024),
                integer(values.get("maxPayloadBytes"), "maxPayloadBytes", 4096),
                backpressure(string(values.get("backpressure"), "backpressure")));
    }

    private static RequestRuntimeConfig requests(Map<String, Object> values) {
        return new RequestRuntimeConfig(
                integer(values.get("maxPending"), "requests.maxPending", 65_536),
                Duration.ofMillis(
                        longValue(values.get("defaultTimeoutMillis"), "requests.defaultTimeoutMillis", 5_000)),
                bool(values.get("pooledPendingRequests"), true));
    }

    private static SchedulerRuntimeConfig scheduler(Map<String, Object> values) {
        requireKeys(
                values,
                "ringloom.runtime.scheduler",
                Set.of("maxTimers", "tickResolutionNanos", "ticksPerWheel", "initialTickAllocation", "pollLimit"));
        return new SchedulerRuntimeConfig(
                integer(values.get("maxTimers"), "scheduler.maxTimers", 1024),
                longValue(values.get("tickResolutionNanos"), "scheduler.tickResolutionNanos", 1_048_576L),
                integer(values.get("ticksPerWheel"), "scheduler.ticksPerWheel", 1024),
                integer(values.get("initialTickAllocation"), "scheduler.initialTickAllocation", 16),
                integer(values.get("pollLimit"), "scheduler.pollLimit", 64));
    }

    private static TracingRuntimeConfig tracing(Map<String, Object> values) {
        requireKeys(
                values,
                "ringloom.runtime.tracing",
                Set.of("enabled", "sampler", "sampleRatio", "propagation", "includeDecodeTime"));
        return new TracingRuntimeConfig(
                bool(values.get("enabled"), false),
                sampler(string(values.get("sampler"), "ringloom.runtime.tracing.sampler")),
                doubleValue(values.get("sampleRatio"), "ringloom.runtime.tracing.sampleRatio", 0.0),
                propagation(string(values.get("propagation"), "ringloom.runtime.tracing.propagation")),
                bool(values.get("includeDecodeTime"), true));
    }

    private static RingloomSerializerConfig serializers(Map<String, Object> values) {
        requireKeys(values, "ringloom.serializers", Set.of("default", "entries"));
        String defaultSerializer = string(values.get("default"), "ringloom.serializers.default");
        Map<String, Map<String, Object>> entries = new HashMap<>();
        for (Map.Entry<String, Object> entry : optionalMap(values.get("entries"), "ringloom.serializers.entries")
                .entrySet()) {
            entries.put(
                    entry.getKey(), optionalMap(entry.getValue(), "ringloom.serializers.entries." + entry.getKey()));
        }
        return new RingloomSerializerConfig(defaultSerializer, entries);
    }

    private static Map<String, RingloomClientRuntimeConfig> clients(Map<String, Object> values) {
        Map<String, RingloomClientRuntimeConfig> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Map<String, Object> client = optionalMap(entry.getValue(), "ringloom.clients." + entry.getKey());
            requireKeys(client, "ringloom.clients." + entry.getKey(), Set.of("service", "routing", "serializer"));
            result.put(
                    entry.getKey(),
                    new RingloomClientRuntimeConfig(
                            entry.getKey(),
                            string(
                                    required(client, "service", "ringloom.clients." + entry.getKey() + ".service"),
                                    "service"),
                            routing(string(client.get("routing"), "routing")),
                            string(client.get("serializer"), "serializer")));
        }
        return result;
    }

    private static Object required(Map<String, Object> map, String key, String path) {
        if (!map.containsKey(key)) {
            throw new IllegalArgumentException("missing required key " + path);
        }
        return map.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String path) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(path + " must be a map");
        }
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(path + " keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> optionalMap(Object value, String path) {
        return value == null ? Map.of() : map(value, path);
    }

    private static void requireKeys(Map<String, Object> values, String path, Set<String> allowed) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("unknown key " + path + "." + key);
            }
        }
    }

    private static String string(Object value, String path) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return text;
    }

    private static int integer(Object value, String path, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        return number.intValue();
    }

    private static Integer optionalInteger(Object value, String path) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        return number.intValue();
    }

    private static long longValue(Object value, String path, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        return number.longValue();
    }

    private static double doubleValue(Object value, String path, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + " must be a number");
        }
        return number.doubleValue();
    }

    private static boolean bool(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException("value must be boolean");
        }
        return bool;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Enum.valueOf(type, value.replace('-', '_').toUpperCase());
    }

    private static IdleStrategyKind idleKind(String value) {
        if (value == null) {
            return IdleStrategyKind.BACKOFF;
        }
        return switch (value) {
            case "busySpin" -> IdleStrategyKind.BUSY_SPIN;
            case "yielding" -> IdleStrategyKind.YIELDING;
            case "sleeping" -> IdleStrategyKind.SLEEPING;
            case "backoff" -> IdleStrategyKind.BACKOFF;
            case "noOp" -> IdleStrategyKind.NO_OP;
            default -> throw new IllegalArgumentException("unknown idle strategy " + value);
        };
    }

    private static MessageExecutionMode executionMode(String value) {
        if (value == null || value.equals("consumerThread")) {
            return MessageExecutionMode.CONSUMER_THREAD;
        }
        return switch (value) {
            case "partitionedWorkers" -> MessageExecutionMode.PARTITIONED_WORKERS;
            case "virtualThreads" -> MessageExecutionMode.VIRTUAL_THREADS;
            default -> throw new IllegalArgumentException("unknown message execution mode " + value);
        };
    }

    private static WorkerBackpressurePolicy backpressure(String value) {
        if (value == null || value.equals("parkConsumer")) {
            return WorkerBackpressurePolicy.PARK_CONSUMER;
        }
        if (value.equals("failFast")) {
            return WorkerBackpressurePolicy.FAIL_FAST;
        }
        throw new IllegalArgumentException("unknown partitioned backpressure " + value);
    }

    private static TracingSamplerKind sampler(String value) {
        if (value == null || value.equals("off")) {
            return TracingSamplerKind.OFF;
        }
        return switch (value) {
            case "alwaysOn" -> TracingSamplerKind.ALWAYS_ON;
            case "traceIdRatio" -> TracingSamplerKind.TRACE_ID_RATIO;
            default -> throw new IllegalArgumentException("unknown tracing sampler " + value);
        };
    }

    private static TracingPropagationMode propagation(String value) {
        if (value == null || value.equals("none")) {
            return TracingPropagationMode.NONE;
        }
        return switch (value) {
            case "application" -> TracingPropagationMode.APPLICATION;
            case "payloadPrefix" -> TracingPropagationMode.PAYLOAD_PREFIX;
            default -> throw new IllegalArgumentException("unknown tracing propagation " + value);
        };
    }

    private static RoutingMode routing(String value) {
        if (value == null || value.equals("loadBalanced")) {
            return RoutingMode.LOAD_BALANCED;
        }
        if (value.equals("direct")) {
            return RoutingMode.DIRECT;
        }
        if (value.equals("leader")) {
            return RoutingMode.LEADER;
        }
        throw new IllegalArgumentException("unknown routing mode " + value);
    }
}
