# Phase 14 — Topic annotation model & codegen

## Objective

Add the topic annotations and the annotation-processor code generation that
turns them into runtime-registered publishers and subscribers. Producers are
annotation-driven and generated (`@RingloomTopicPublisher` + `@RingloomTopicPublish`);
subscribers are annotation-only and registered at startup (`@RingloomTopicHandler`).

This phase owns: the three annotations, the `GeneratedTopicPublisherBinding` /
`GeneratedTopicHandlerBinding` / `GeneratedTopicDispatcher` SPIs, the processor's
topic codegen, and the validation rules. It consumes the runtime hooks from phase
12, the dispatcher SPI from phase 13, and the publisher runtime from phase 15.

This phase does **not** own the ack callback registry internals (phase 15); it
generates the publisher binding that *uses* them.

## Annotations

All three annotations live in `io.ringloom.framework.annotation`, source
retention, matching the existing set (`@RingloomHandler`, `@RingloomRequest`).

### `@RingloomTopicPublisher`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface RingloomTopicPublisher {
    /** The topic name. Deterministic topic_id = hash(name) is computed natively. */
    String topic();

    /** Default queue geometry applied on first registration (first-wins on the broker). */
    String rollScheme() default "FAST_DAILY";

    int retentionCycles() default 0;   // 0 = keep all

    /** Optional client alias to publish through; empty uses a default client for the topic. */
    String client() default "";
}
```

Target: interfaces. An interface annotated with this declares a publisher; its
`@RingloomTopicPublish` methods become the generated proxy's publish methods.

### `@RingloomTopicPublish`

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface RingloomTopicPublish {
    /** Serializer name used to encode the payload. */
    String serializer();

    /** Acknowledgement mode: FIRE_AND_FORGET (default) or REPLICATE_ONCE. */
    TopicAckMode ackMode() default TopicAckMode.FIRE_AND_FORGET;

    /** Error policy: STATUS (default) or THROW. */
    ErrorPolicy errorPolicy() default ErrorPolicy.STATUS;
}
```

The method's first parameter is the typed payload (application record/flyweight);
the generated proxy encodes it via the serializer and publishes. Method shapes
(see §Generated publisher methods):

- Fire-and-forget: `int publish(Quote quote)` — returns status.
- Replicate-once callback: `int publishAck(Quote quote, AckCallback callback,
  Object userContext)` — registers the callback keyed by the assigned publish
  index before returning.

`@RingloomTopicPublish` is only valid on methods of a `@RingloomTopicPublisher`
interface; the processor rejects it elsewhere.

### `@RingloomTopicHandler`

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface RingloomTopicHandler {
    /** The topic name to subscribe to. */
    String topic();

    /** Start position when (re)joining: EARLIEST (replay) or LATEST (tail). */
    TopicStart start() default TopicStart.EARLIEST;

    /** Serializer name used to decode the payload. */
    String serializer();

    /** Optional partition-key field path / extractor name for partitioned dispatch. */
    String partitionKey() default "";
}
```

Target: instance methods on a `@RingloomServiceComponent` class, mirroring
`@RingloomHandler`. The handler signature follows the same two profiles:

- Ergonomic: `int onQuote(Quote quote, TopicContext context)`.
- Hot-path: `int onQuote(QuoteFlyweight quote, TopicContext context)` — borrowed
  flyweight / `MemorySegment` valid only during the call.

`@RingloomTopicHandler` methods dispatch through the generated
`GeneratedTopicDispatcher` keyed by topic id (phase 13), not the template-id
dispatcher used by `@RingloomHandler`.

## Generated artifacts

For each `@RingloomTopicPublisher` interface, the processor generates:

1. `<Name>_RingloomTopicPublisher` — the proxy implementation. Holds the
   registered native `TopicPublisher` handle, the serializer encoder per publish
   method, and (for `replicate_once`) the ack registry slot from phase 15. Stable
   constructor signature for IoC containers (see §Constructor contracts).
2. A `GeneratedTopicPublisherBinding` instance contributed to
   `GeneratedRingloomApplication.topicPublishers()`.

For `@RingloomTopicHandler` methods, the processor generates:

1. A `<Application>_RingloomTopicDispatcher` implementing
   `GeneratedTopicDispatcher`, with a `switch` on topic id selecting the handler
   branch (reusing per-thread `TopicContext` and flyweights).
2. Optional `GeneratedPartitionKeyExtractor`s keyed by topic id for handlers that
   declare `partitionKey`.
3. `GeneratedTopicHandlerBinding`s contributed to
   `GeneratedRingloomApplication.topicHandlers()`.

No `META-INF/services` changes beyond the existing generated application
registration; topic publishers/handlers are surfaced through the
`GeneratedRingloomApplication` SPI extensions defined in phase 12.

## SPI extensions to `GeneratedRingloomApplication`

```java
default List<GeneratedTopicPublisherBinding> topicPublishers() { return List.of(); }
default List<GeneratedTopicHandlerBinding> topicHandlers() { return List.of(); }
default GeneratedTopicDispatcher topicDispatcher() { return null; }

/** Topic-id -> partition-key extractor for topic handlers, when present. */
default Map<Long, GeneratedPartitionKeyExtractor> topicPartitionKeyExtractors() { return Map.of(); }

/** True when the generated application uses any topic feature. */
default boolean requiresTopicBindings() {
    return !topicPublishers().isEmpty() || topicDispatcher() != null;
}
```

### Binding SPIs

```java
public interface GeneratedTopicPublisherBinding {
    /** The public publisher interface type. */
    Class<?> publisherType();
    /** The topic name. */
    String topic();
    /** The TopicConfig to register (from @RingloomTopicPublisher geometry). */
    TopicConfig topicConfig();
    /**
     * Creates the publisher instance: registers the publication on the supplied
     * client (resolving topic_id), binds the ack registry from phase 15, and
     * returns the generated proxy.
     */
    Object create(RingloomRuntime runtime, RingloomClient client,
                  SerializerRegistry serializers, TopicAckRegistry ackRegistry);
}

public interface GeneratedTopicHandlerBinding {
    String topic();
    TopicStart start();
    String serializer();
    /** Field path / extractor name, or null. */
    String partitionKey();
}
```

## Runtime registration flow (built on phase 12)

In `RingloomRuntime.start()`, after generated clients and the message dispatcher
are created, when `requiresTopicBindings()` is true:

1. **Publishers:** for each `GeneratedTopicPublisherBinding`, resolve the target
   `RingloomClient` (from `client()` alias or a default client), call
   `client.registerTopicPublication(topic, topicConfig())` (phase 12 / cross-repo
   binding), then `binding.create(runtime, client, serializers, ackRegistry)`.
   Store the result as a generated client (`generatedClients` map) keyed by
   publisher type, mirroring service clients.
2. **Subscriptions:** for each `GeneratedTopicHandlerBinding`, call
   `topicRuntime.subscribe(topic, start())`. Record topic name → topic id (from
   the subscription response) and the handler binding, populating phase 12's
   `TopicPollState` array and `topicIdToName` map.
3. **Dispatcher:** wire `GeneratedTopicDispatcher` + `topicPartitionKeyExtractors`
   into the `TopicMessageSource` and the execution policies (phase 13).
4. Start the poll loop / prefetcher (phase 12).

Generated handler component instances are obtained via the existing
`componentTypes()` / `withComponents(...)` path; `@RingloomTopicHandler` methods
live on `@RingloomServiceComponent` classes already managed by the dispatcher.

## Generated publisher methods

For a `@RingloomTopicPublisher` interface:

```java
@RingloomTopicPublisher(topic = "quotes")
public interface QuotesPublisher {
    @RingloomTopicPublish(serializer = "sbe")
    int publish(Quote quote);

    @RingloomTopicPublish(serializer = "sbe", ackMode = TopicAckMode.REPLICATE_ONCE)
    int publishAck(Quote quote, AckCallback callback, Object userContext);
}
```

the generated `QuotesPublisher_RingloomTopicPublisher`:

- Holds `TopicPublisher handle` (from registration), `MessageEncoder<Quote> encoder`
  (resolved at startup from the serializer registry), and `TopicAckRegistry acks`.
- `publish(Quote)`:
  1. Compute encoded length via `encoder.encodedLength(quote, encodeCtx)`.
  2. Publish via the zero-copy claim path where the serializer supports it, else
     encode into a staging segment and call `handle.publish(segment)`. Returns
     status. No ack tracking.
- `publishAck(Quote, AckCallback, Object)`:
  1. Encode as above.
  2. Call `handle.publish(segment, REPLICATE_ONCE, correlationId, outIndexHolder)`
     with a monotonic per-publisher correlation id (phase 15).
  3. On `OK`, register `(outIndexHolder[0], callback, userContext)` in `acks`
     **before** returning.
  4. Return status.

The encoder, encode context, claim, and `outIndexHolder` are per-thread reusable
objects owned by the proxy (or by a `DirectPublishContext` passed by the caller),
so the hot path allocates nothing. The `AckCallback` and `userContext` are
caller-owned (stateless singleton / pooled state), matching the
`ResponseCallback` rule from request/response.

## Generated topic dispatcher

```java
public final class <App>_RingloomTopicDispatcher implements GeneratedTopicDispatcher {
    // per-thread reusable TopicContext + flyweights
    private final ThreadLocal<TopicContext> contexts = ...;

    @Override
    public int onTopicMessage(TopicMessage message, TopicContext context) {
        switch (context.topicId()) {
            case QUOTES_TOPIC_ID: return onQuote(message, context);
            // ...
            default: return RingloomHandlerStatus.UNKNOWN_TOPIC; // counted/dropped
        }
    }

    private int onQuote(TopicMessage message, TopicContext context) {
        QuoteFlyweight flyweight = quoteFlyweightThreadLocal.get();
        flyweight.wrap(message.payloadSegment(), decodeCtxThreadLocal.get());
        return quotesHandler.onQuote(flyweight, context);
    }
}
```

- `switch` on topic id — no map lookup, no boxing.
- Handler component instances are fields resolved at construction (from
  `withComponents`).
- Unknown topic ids return a status and are counted (defensive; should not happen
  since subscriptions are compile-time known).
- Per-thread `TopicContext` and flyweights; the borrowed payload is valid only
  for the call (phase 13 contract).

## Partition-key codegen for topics

When a `@RingloomTopicHandler` declares `partitionKey`, the processor generates a
`GeneratedPartitionKeyExtractor` that reads the field path from the borrowed
payload segment and returns a primitive `long`, identical to the service-message
SBE extractor. It is registered in `topicPartitionKeyExtractors()` keyed by topic
id. The partitioned-worker policy (phase 13) uses it via a generated `switch`.

Keyless handlers in partitioned mode route to a single dedicated worker (worker
index derived from topic id) to preserve per-topic order; this is generated, not
runtime-decided.

## Validation (processor responsibilities)

The processor fails compilation for:

1. `@RingloomTopicPublish` on a method of a non-`@RingloomTopicPublisher`
   interface, or `@RingloomTopicPublisher` on a non-interface.
2. Duplicate topic names across `@RingloomTopicPublisher` interfaces in the same
   service (topic id collision) — reported at compile time using the documented
   hash, consistent with the broker's first-wins rule.
3. A `@RingloomTopicHandler` referencing an unknown serializer, or an unsupported
   handler signature (must return `int`, must take payload + `TopicContext`).
4. A `@RingloomTopicPublish` method whose payload type the named serializer cannot
   encode, or whose replicate-once variant lacks an `AckCallback` parameter.
5. Duplicate topic-handler registrations for the same topic on the same
   subscription start position (ambiguous dispatch).
6. `partitionKey` on a handler whose declared serializer does not expose that
   field path (SBE-backed), or on a keyless payload type.

It warns for:

- A topic used by both a publisher and a handler in the same service (legal — a
  service can publish and subscribe to the same topic — but worth flagging for
  loop review).

## Config reconciliation

Generated metadata and YAML `topics.handlers` may both describe handlers. Rules:

1. Generated metadata (from `@RingloomTopicHandler`) is authoritative for
   serializer and partitionKey (they are codegen inputs).
2. YAML may override `start` (EARLIEST/LATEST) for operational tuning without
   recompiling; a YAML `start` for a topic not present in generated metadata is a
   startup error.
3. `publisherDefaults` from YAML apply only to publishers that omit geometry on
   the annotation; annotation geometry always wins.

`TopicsRuntimeConfig` (phase 12) is the merge target; the runtime resolves
conflicts per the above before registering.

## Constructor contracts (IoC)

Generated publisher classes must have a stable constructor so any IoC container
can instantiate them without reflection over RingLoom annotations:

```java
public QuotesPublisher_RingloomTopicPublisher(
    RingloomRuntime runtime,
    TopicPublisher handle,
    MessageEncoder<Quote> encoder,
    TopicAckRegistry acks) { ... }
```

Generated topic dispatchers and handler components follow the existing
constructor-injection conventions.

## Testing

Processor tests (compile-time, mirroring `RingloomFrameworkProcessorTest`):

1. A publisher interface + publish method generates a `_RingloomTopicPublisher`
   with the expected methods and constructor.
2. A handler method generates a `_RingloomTopicDispatcher` with a topic-id
   `switch` selecting the right branch.
3. A `partitionKey` generates the matching extractor.
4. All validation rules produce the expected compile errors.

Runtime integration tests (topics-enabled broker):

1. Generated publisher + handler in two services: publish N from one, the other
   receives all N in order (consumerThread).
2. `replicate_once` publish method registers an ack callback that completes
   (phase 15 wires completion).
3. `start = LATEST` vs `EARLIEST` behave as expected.
4. Partitioned dispatch for a topic with a declared key preserves per-key order.

## Acceptance criteria

1. `@RingloomTopicPublisher` / `@RingloomTopicPublish` / `@RingloomTopicHandler`
   are declared with source retention and the documented attributes.
2. The processor generates publisher proxies, a topic dispatcher, and
   partition-key extractors, surfacing them via the `GeneratedRingloomApplication`
   SPI.
3. Generated topic dispatch keys on topic id via a switch with zero per-message
   allocation.
4. All validation rules fail compilation with clear messages.
5. Generated publisher/handler code round-trips messages end-to-end against a
   topics-enabled broker.

## Dependencies

- Phase 12 (runtime registration hooks, `TopicRuntime`).
- Phase 13 (`GeneratedTopicDispatcher`, `TopicMessage`/`TopicContext`,
  partitioned dispatch).
- Phase 15 (`TopicAckRegistry`, ack completion) — the generated publisher binding
  consumes it; this phase defines the binding shape, phase 15 the registry.
- Cross-repo binding (`TopicPublisher`, `TopicConfig`, `TopicAckMode`,
  `TopicStart`).
