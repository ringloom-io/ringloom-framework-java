# Phase 12 — Topic runtime & polling

## Objective

Add the `TopicRuntime` sub-component that owns topic publishers and
subscriptions, drives subscriber polling, runs the maintenance/prefetcher thread,
and polls replicated-count feedback for the ack registry. This phase wires the
low-level topic bindings (see the cross-repo spec
[`docs/topics/12-java-topic-bindings.md`](https://github.com/ringloom-io/ringloom/blob/main/docs/topics/12-java-topic-bindings.md))
into the framework lifecycle and provides the `pollTopics()` entry point that
later phases (dispatch, publishers, annotations) build on.

This phase does **not** cover handler dispatch semantics (phase 13), the
annotation model/codegen (phase 14), or the ack callback registry (phase 15).
Those phases plug into the hooks defined here.

## Deliverables

1. `TopicsRuntimeConfig` and related config records.
2. `TopicRuntime` owning publishers, subscriptions, the prefetcher thread, and
   the control-thread ack-feedback poll.
3. `RingloomRuntime` integration: start/stop, `pollTopics()`, and coalescing the
   topic poll into the message event loop.
4. YAML configuration for topics.
5. Tests for lifecycle, polling, coalescing, and prefetcher thread behavior.

## Non-overlap with native design

Consistent with the standing non-goal, the framework does **not** run a broker,
open a master queue for append, or drive replication. All persistence,
replication, leadership, and epoch fencing are broker responsibilities. The
framework sees topics as: a publisher handle (send to leader), a subscription
handle (read local replica/master via tailer), and control-plane notifications
(leader change, ack feedback). This phase consumes those and nothing more.

## Configuration

### `TopicsRuntimeConfig`

```java
public record TopicsRuntimeConfig(
        boolean enabled,
        boolean coalesceWithMessages,
        TopicPrefetcherConfig prefetcher,
        TopicPublisherDefaults publisherDefaults,
        Map<String, TopicHandlerConfig> handlers) {
    public static TopicsRuntimeConfig disabled() {
        return new TopicsRuntimeConfig(false, true, TopicPrefetcherConfig.defaults(),
                TopicPublisherDefaults.defaults(), Map.of());
    }
    // compacting constructor validates fields (poll limits > 0, etc.)
}
```

| Field | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Master switch. When `false`, `pollTopics()` is a no-op, no prefetcher thread starts, and generated publisher/handler registration is skipped. |
| `coalesceWithMessages` | `true` | When `true` and runtime mode is not `external`, the topic poll runs inside the message event loop. When `false`, topics are only polled via explicit `pollTopics()`. |
| `prefetcher` | `defaults()` | Maintenance thread settings (see below). |
| `publisherDefaults` | `defaults()` | Default `TopicConfig` applied on first publication registration. |
| `handlers` | `Map.of()` | Per-topic handler metadata (start position, serializer, partition key). Populated by YAML and/or generated metadata. |

### `TopicPrefetcherConfig`

```java
public record TopicPrefetcherConfig(
        Integer cpuAffinity,     // optional pin; null = no pinning
        int pollLimit,           // queues advanced per maintenance tick
        long intervalMicros) {   // tick interval; busySpin when 0
    public static TopicPrefetcherConfig defaults() {
        return new TopicPrefetcherConfig(null, 64, 0);
    }
}
```

The prefetcher thread loops calling `maintenancePoll(pollLimit)` round-robin
across all open subscriptions, then idles via the configured idle strategy
(`intervalMicros == 0` ⇒ busy-spin; otherwise `LockSupport.parkNanos`). Pinning
uses the existing `CpuAffinity.setCurrentThreadAffinity` (Linux; warn + continue
elsewhere, matching the event-loop pinning contract).

### `TopicPublisherDefaults` / `TopicHandlerConfig`

```java
public record TopicPublisherDefaults(String rollScheme, int retentionCycles, int flags) {
    public TopicConfig toTopicConfig() { ... }   // builds the binding TopicConfig
}

public record TopicHandlerConfig(
        String topic,
        TopicStart start,            // EARLIEST / LATEST
        String serializer,           // resolved against SerializerRegistry
        String partitionKey) {       // optional field path / extractor name; null = no partitioning
}
```

These mirror the binding types (`TopicConfig`, `TopicStart`) without depending on
native handles. `TopicPublisherDefaults` converts to the binding `TopicConfig` at
registration time.

`TopicsRuntimeConfig` is added to `RingloomApplicationConfig` as a new `topics`
field with a `disabled()` default, preserving source compatibility for
applications that do not use topics.

## YAML

Extends the existing `ringloom:` tree:

```yaml
ringloom:
  topics:
    enabled: true
    coalesceWithMessages: true
    prefetcher:
      cpuAffinity: 2
      pollLimit: 64
      intervalMicros: 0
    publisherDefaults:
      rollScheme: FAST_DAILY
      retentionCycles: 0
    handlers:
      quotes:
        topic: quotes
        start: earliest
        serializer: sbe
        partitionKey: instrumentId
```

The YAML loader (`YamlRingloomConfigLoader`) maps `topics.handlers` into the
`handlers` map. Handler config may also be contributed by generated metadata
(phase 14); when both YAML and generated metadata describe the same topic, the
processor-generated metadata is authoritative and a mismatch is a startup error
(see phase 14).

When `topics.enabled` is absent, it defaults to `false` and the entire `topics:`
block is ignored.

## TopicRuntime

`TopicRuntime` is owned by `RingloomRuntime` and is only constructed when
`topics.enabled` is `true`. Responsibilities (mirroring `RingloomRuntime`'s own
lifecycle split):

```java
public final class TopicRuntime implements AutoCloseable {
    public TopicRuntime(
            RingloomService service,
            TopicsRuntimeConfig config,
            SerializerRegistry serializers,
            Logger logger);

    /** Register a topic publication at startup. Returns the native TopicPublisher. */
    public TopicPublisher registerPublication(String topicName, TopicConfig config);

    /** Open a topic subscription at startup. Returns the native TopicSubscription. */
    public TopicSubscription subscribe(String topicName, TopicStart start);

    /** Advance all subscriptions and dispatch received messages. */
    public int pollTopics();

    /** Round-robin maintenance poll across all subscriptions (prefetcher thread body). */
    void runMaintenance();

    /**
     * Polls each publisher's replicated-count and leader-epoch accessors and drives
     * the matching ack registries. Called by the framework control thread each tick.
     * Phase 15 wires the registries; this method reads the binding accessors
     * (TopicPublisher.replicatedCount() / leaderEpoch()) and feeds the registry.
     */
    void pollAckFeedback();

    @Override public void close();
}
```

### Lifecycle

1. **Construction** (during `RingloomRuntime` construction, before `start()`):
   stores config, serializers, logger. No native calls yet.
2. **Start** (inside `RingloomRuntime.start()`, after the service is up and
   generated clients/dispatcher are created): the framework calls
   `TopicRuntime.start()`, which:
   - Verifies topic symbols are present in the loaded native library
     (`RingloomClient.registerTopicPublication`/`subscribeTopic` throw a clear
     error otherwise). This is the framework-side ABI gate.
   - Starts the prefetcher thread (unless `external` runtime mode + the symbol is
     absent; see below).
3. **Stop** (inside `RingloomRuntime.close()`): closes all publishers and
   subscriptions (each is `AutoCloseable`), stops the prefetcher thread, and
   completes all pending acks with a shutdown status (phase 15).

### Owned state

- `Map<String, TopicPublisher> publishers` — keyed by topic name.
- `Map<String, TopicSubscription> subscriptions` — keyed by topic name.
- `Map<Long, String> topicIdToName` — for routing ack feedback / leader-change by
  `topic_id` (the control plane keys these by id; the framework registers the
  name→id mapping when a publication/subscription response is received).
- `TopicPollState[] pollStates` — a flat array (one entry per subscription) used
  by the poll loop to avoid per-poll allocation: a reusable `TopicPollResult`,
  the bound handler binding, and the last-seen index.

All three maps are populated **only at startup** (all subscriptions are
compile-time known — phase 14; publishers too). The poll loop reads them without
locking after start. `close()` is the only mutator after start.

## Polling

### `pollTopics()`

```java
public int pollTopics() {
    int dispatched = 0;
    for (TopicPollState state : pollStates) {
        int status;
        while ((status = state.subscription.poll(state.result)) == RingloomStatus.OK) {
            dispatched++;
            topicMessageSource.offer(state.subscription.topic(), state.result);
            if (dispatched >= perSubscriptionPollLimit) break;
        }
        // NOT_READY / END_OF_HISTORY => subscription idle; continue
    }
    return dispatched;
}
```

Design points:

1. **Round-robin** over all subscriptions with a per-subscription message budget
   (`runtime.messages.pollLimit()`, shared with the message consumer). This
   prevents one busy topic from starving others.
2. **Borrowed payloads.** `state.result.payloadSegment()` is valid only until the
   next `poll` of *that* subscription. `topicMessageSource.offer` must consume or
   copy before returning (phase 13 defines the copy rules per execution mode).
3. **Zero per-poll allocation.** `TopicPollState` and its `TopicPollResult` are
   preallocated at start. The empty-poll status is treated as idle, not error.
4. **Not thread-safe for concurrent pollers.** Exactly one thread polls topics at
   a time — either the message event-loop thread (coalesced) or the caller of
   `pollTopics()` (external mode). This matches the single-poller contract of the
   message consumer.

### `TopicMessageSource`

A small adapter that lets topic messages flow through the same
`MessageExecutionPolicy` as service messages (phase 13 details dispatch). Its
`offer(topicName, TopicPollResult)` builds a reusable `TopicMessage` (topic name,
borrowed payload segment, index) and hands it to the policy. It is owned by the
polling thread.

### Coalescing into the message event loop

When `coalesceWithMessages` is `true` and runtime mode is `DEDICATED` or
`SHARED`, `RingloomRuntime` composes a topic-poll step into the message agent.
Concretely, `MessageConsumerAgent` (or the shared `CompositeAgent`) calls
`topicRuntime.pollTopics()` after each `consumer.poll(...)` batch. This gives
automatic subscriber polling without an extra thread, at the cost of sharing the
message thread.

| Runtime mode | `coalesceWithMessages=true` | `coalesceWithMessages=false` |
|---|---|---|
| `DEDICATED` | Topic poll runs on the message thread after each message batch. | Topic poll only via explicit `pollTopics()` (app must call it — unusual). |
| `SHARED` | Topic poll runs on the shared thread after each message batch. | Same as dedicated=false. |
| `EXTERNAL` | Coalescing is ignored; the app calls `pollTopics()` itself (and `pollControl()`/`pollMessages()` per the external contract). | App calls `pollTopics()` itself. |

In `EXTERNAL` mode, `RingloomRuntime` exposes `pollTopics()` so an externally
managed loop can drive control, messages, and topics together.

`RingloomRuntime.pollTopics()` delegates to `topicRuntime.pollTopics()` when
topics are enabled and is a no-op returning `0` otherwise.

## Prefetcher thread

The prefetcher thread is the framework analogue of the broker's
prefetcher/maintenance thread (`topics-architecture.md` §6): it keeps read pages
resident ahead of each subscriber tailer so the poll path faults no pages.

```java
private final class PrefetcherAgent implements Agent {
    public int doWork() {
        int total = 0;
        for (TopicPollState state : pollStates) {
            total += state.subscription.maintenancePoll(config.prefetcher().pollLimit());
        }
        return total;
    }
}
```

- Started once via an `EventLoop` (reusing the existing `EventLoop`/`Agent`
  machinery) with the configured idle strategy, or a raw thread if simpler. It is
  **separate** from the control and message loops.
- It calls only `TopicSubscription.maintenancePoll`, never `poll`. It must not
  advance the read cursor the poll path consumes.
- If the `ringloom_topic_subscription_maintenance_poll` native symbol is absent
  (older build), `TopicRuntime.start()` logs a warning and skips starting the
  thread; polling still works but latency is less predictable (page faults may
  appear on the poll path).
- CPU affinity from `prefetcher.cpuAffinity` is applied via the same
  `CpuAffinity` path as the event loops; an unsupported platform warns and
  continues unpinned.

## Required binding surfaces (from cross-repo spec)

`TopicRuntime` depends on the following `RingloomClient`/binding methods, all
defined in
[`docs/topics/12-java-topic-bindings.md`](https://github.com/ringloom-io/ringloom/blob/main/docs/topics/12-java-topic-bindings.md):

- `RingloomClient.registerTopicPublication(name, TopicConfig) -> TopicPublisher`
- `RingloomClient.subscribeTopic(name, TopicStart) -> TopicSubscription`
- `TopicSubscription.poll(TopicPollResult) -> int` (with `NOT_READY` empty status)
- `TopicSubscription.maintenancePoll(int) -> int`
- `TopicPublisher.publish(...)` / `isAcked(long)` (used by phase 15)

Framework startup must fail clearly if topic symbols are absent while
`topics.enabled` is `true`.

## RingloomRuntime integration

Changes to `RingloomRuntime`:

1. New `TopicRuntime topicRuntime` field; constructed in the constructor when
   `config.topics().enabled()` is `true`, else `null`.
2. In `start()`, after generated clients and dispatcher are created: if
   `topicRuntime != null`, call `topicRuntime.start()` and register generated
   topic publishers/handlers (phase 14 supplies the metadata via
   `GeneratedRingloomApplication` extensions).
3. `pollTopics()` public method delegating to `topicRuntime` (no-op if disabled).
4. Coalescing: when enabled and mode != `external`, the message agent composes a
   topic-poll step.
5. In `close()`: `closeQuietly(topicRuntime, "topic runtime")` after closing the
   message/control loops and before closing the service (so native handles are
   released while the service is still up).
6. Expose `topicRuntime()` (package-private) for phase 13/15 to reach ack
   feedback and leader-change hooks, and for the control loop to dispatch those
   notifications (the control loop already runs on the control thread).

## Generated application SPI (hooks for later phases)

Phase 14 extends `GeneratedRingloomApplication` with topic metadata; this phase
defines the minimal hooks so phase 14 can plug in without touching `TopicRuntime`
internals:

```java
// in GeneratedRingloomApplication (phase 14 implements these)
default List<GeneratedTopicPublisherBinding> topicPublishers() { return List.of(); }
default List<GeneratedTopicHandlerBinding> topicHandlers() { return List.of(); }
default GeneratedTopicDispatcher topicDispatcher() { return null; }
default boolean requiresTopicBindings() { return false; }
```

`TopicRuntime.start()` reads these (when non-empty) to register publishers and
subscriptions and wire the dispatcher. Phase 14 fills them in; until then,
imperative `registerPublication`/`subscribe` (used by tests) drive registration.

## Testing

Unit tests (no broker):

1. `TopicsRuntimeConfig.disabled()` produces a no-op `pollTopics()` and does not
   start a prefetcher thread.
2. Config validation: negative poll limits, invalid roll-scheme length rejected.
3. `TopicPublisherDefaults.toTopicConfig()` round-trips fields.

Integration tests (topics-enabled broker via extended `TestBroker`):

1. With `enabled=false`, `pollTopics()` returns 0 and never touches native topic
   symbols (guard against a build lacking topic support).
2. Register a publication + subscribe `EARLIEST`; publish N; `pollTopics()`
   dispatches N messages in order.
3. `LATEST` subscription misses pre-publish messages, sees only later ones.
4. Coalesced mode: a dedicated runtime with `coalesceWithMessages=true` delivers
   topic messages without any explicit `pollTopics()` call.
5. External mode: topics delivered only when `pollTopics()` is called by the app.
6. Prefetcher thread: with data flowing, `maintenancePoll` is called repeatedly
   and the poll path does not fault (observable via the maintenance counter); with
   the maintenance symbol absent, the thread is skipped and a warning is logged.
7. Lifecycle: closing the runtime closes all publishers/subscriptions and stops
   the prefetcher thread; a second close is a no-op.
8. Topic symbols absent + `enabled=true`: `start()` throws the clear
   "native library does not support topics" error.

Allocation tests:

1. Steady-state `pollTopics()` (after warmup) allocates nothing on the idle and
   message paths, using reused `TopicPollState`/`TopicPollResult`.

## Acceptance criteria

1. `TopicRuntime` starts/stops cleanly, owns all topic handles, and is closed
   before the service on shutdown.
2. `pollTopics()` polls all subscriptions round-robin with zero per-poll
   allocation and dispatches borrowed payloads.
3. Coalescing runs the topic poll on the message thread when configured; external
   mode requires explicit `pollTopics()`.
4. The prefetcher thread drives `maintenancePoll` off the poll path, or is
   skipped with a warning when the symbol is absent.
5. Topics disabled (`enabled=false`) is a complete no-op with no native topic
   symbol usage.
6. All lifecycle, polling, coalescing, and allocation tests pass against a
   topics-enabled broker.

## Dependencies

- Cross-repo: [`docs/topics/12-java-topic-bindings.md`](https://github.com/ringloom-io/ringloom/blob/main/docs/topics/12-java-topic-bindings.md)
  (low-level `TopicPublisher`/`TopicSubscription`/`TopicPollResult`/gating).
- Framework: phase 13 (dispatch), phase 14 (annotations/codegen), phase 15 (ack
  registry) build on the hooks defined here.
