# Phase 13 — Topic dispatch

## Objective

Route topic messages (read from a subscription tailer) through the existing
`MessageExecutionPolicy` so `@RingloomTopicHandler` methods run under the same
`consumerThread` / `partitionedWorkers` / `virtualThreads` semantics as service
message handlers — including partition-key ordering and the borrowed-payload
contract.

This phase owns: `TopicMessage` / `TopicContext`, the `TopicMessageSource`
adapter, the generated `GeneratedTopicDispatcher` SPI, partition-key extraction
for topics, and the copy rules for async execution modes. It consumes the poll
loop and `TopicPollResult` from phase 12 and is consumed by the annotation
codegen in phase 14.

## Why reuse the execution policy

Topic messages and service messages differ only in source and routing identity:

| Aspect | Service message | Topic message |
|---|---|---|
| Source | message ring buffer (broker → service) | local ringloom-queue tailer (mmap) |
| Identity key | `templateId` | topic id (= hash of topic name) |
| Addressing | source/target node+service ids | none (broadcast; one global order per topic) |
| Execution needs | consumer/partitioned/virtual, partition ordering | identical |

The execution policy, partition workers, SPSC slots, per-thread contexts, and
ordering guarantees are identical. Duplicating them for topics would double the
hot-path machinery for no benefit. Reusing them requires only (a) a context type
that carries topic identity instead of routing ids, and (b) a dispatch entry that
keys on topic id rather than template id.

## Deliverables

1. `TopicMessage` — borrowed-payload view carrying topic identity + index.
2. `TopicContext` — per-thread mutable context for topic handlers.
3. `TopicMessageSource` — adapter that feeds topic messages into
   `MessageExecutionPolicy` via the dispatcher.
4. `GeneratedTopicDispatcher` SPI — topic-id-keyed dispatch contract.
5. Partition-key extraction for topics (generated extractor plug-in).
6. Copy rules per execution mode (mirroring the service-message rules).
7. Tests for all three execution modes on the topic path.

## Message and context types

### `TopicMessage`

A reusable, borrowed-payload view distinct from `RingloomMessage` (which is tied
to the service ring buffer). It does **not** implement `RingloomMessage`; instead
the dispatcher accepts it via a dedicated method (see SPI below).

```java
public final class TopicMessage {
    private long topicId;
    private MemorySegment payloadSegment = MemorySegment.NULL;
    private long index;          // ringloom-queue index (total order)

    void updateFrom(long topicId, MemorySegment payloadSegment, long index) { ... }

    public long topicId() { return topicId; }
    public MemorySegment payloadSegment() { return payloadSegment; }  // borrowed
    public long index() { return index; }
}
```

- Owned by the polling thread; one reusable instance per `TopicPollState`.
- `payloadSegment` is borrowed from `TopicPollResult` and is valid only until the
  next poll of that subscription (phase 12). Async execution modes must copy
  before returning from the poll callback (§4).

### `TopicContext`

A per-thread mutable context for topic handlers, analogous to `MessageContext`
but carrying topic identity and the read index instead of routing ids. It is
owned by a single event-loop or worker thread.

```java
public final class TopicContext {
    private RingloomRuntime runtime;
    private long topicId;
    private String topicName;
    private long index;          // ringloom-queue index of the current message
    private MemorySegment payloadSegment = MemorySegment.NULL;
    private Object tracingContext;

    public TopicContext() {}
    public TopicContext(RingloomRuntime runtime) { ... }

    public void updateFrom(TopicMessage message, String topicName) { ... }

    // accessors: runtime(), topicId(), topicName(), index(), payloadSegment(),
    //            tracingContext(); setters for tracingContext()/payloadSegment()
}
```

Like `MessageContext`, `TopicContext` supports `updateCopied(...)` for the
partitioned-worker handoff (the worker slot copies payload bytes into
worker-owned storage, then builds a `TopicContext` over the copy).

## Dispatcher SPI

A dedicated `GeneratedTopicDispatcher` (separate from
`GeneratedMessageDispatcher`, which keys on template id) is implemented by the
generated topic dispatcher class (phase 14). The execution policy's topic path
calls it.

```java
public interface GeneratedTopicDispatcher {
    /**
     * Dispatches a topic message using a prepared topic context.
     *
     * @param message the borrowed topic message, or null when dispatching from a copied context
     * @param context the topic context describing the message being dispatched
     * @return the RingLoom handler status code
     */
    int onTopicMessage(TopicMessage message, TopicContext context);

    /**
     * Dispatches a copied topic context that no longer carries the original TopicMessage.
     *
     * @param context the copied topic context
     * @return the handler status code
     */
    default int onContextTopicMessage(TopicContext context) {
        return onTopicMessage(null, context);
    }
}
```

The generated dispatcher keys on `context.topicId()` (or `message.topicId()`) via
a generated `switch` to the matching `@RingloomTopicHandler` branch — not a map
lookup — mirroring how `GeneratedMessageDispatcher` keys on template id. It owns
reusable `TopicContext` instances per event-loop/worker thread and reusable
flyweights, exactly as the message dispatcher owns `MessageContext` instances.

## TopicMessageSource and the policy bridge

`TopicMessageSource` (constructed once per polling thread in phase 12) turns a
`TopicPollResult` into a dispatched message. Its `offer` method is the single
entry the poll loop calls:

```java
public final class TopicMessageSource {
    private final MessageExecutionPolicy policy;
    private final GeneratedTopicDispatcher dispatcher;
    private final TopicMessage message;          // reusable
    private final TopicContext context;          // reusable, consumer-thread

    public TopicMessageSource(MessageExecutionPolicy policy,
                              GeneratedTopicDispatcher dispatcher,
                              RingloomRuntime runtime) { ... }

    /** Consumer-thread hot path: update the reusable message/context and dispatch. */
    public void offer(String topicName, long topicId, TopicPollResult result) {
        message.updateFrom(topicId, result.payloadSegment(), result.index());
        context.updateFrom(message, topicName);
        // For consumerThread: dispatcher runs inline on this thread.
        // For partitionedWorkers/virtualThreads: the policy handles handoff/copy.
        policy.onTopicMessage(message, context);   // <-- new policy method (see below)
    }
}
```

### Extending `MessageExecutionPolicy`

`MessageExecutionPolicy` currently exposes only `onMessage(RingloomMessage,
MessageContext)`. Add a default `onTopicMessage` method that each policy
implements as needed:

```java
public interface MessageExecutionPolicy extends AutoCloseable {
    int onMessage(RingloomMessage message, MessageContext ingressContext);

    /**
     * Dispatches a topic message. Default implementation throws; topic-capable
     * policies override it.
     */
    default int onTopicMessage(TopicMessage message, TopicContext ingressContext) {
        throw new UnsupportedOperationException("policy does not support topics");
    }

    @Override default void close() {}
}
```

Each policy gains a `GeneratedTopicDispatcher` reference (passed in via a new
constructor overload or a setter used at runtime construction) so it can dispatch
topics. When no topic dispatcher exists (no `@RingloomTopicHandler` in the app),
`onTopicMessage` is never called and the default throwing body is unreachable.

## Per-mode dispatch behavior

### consumerThread

`ConsumerThreadExecutionPolicy.onTopicMessage` mirrors its `onMessage`:

```java
public int onTopicMessage(TopicMessage message, TopicContext context) {
    // message/context already updated by TopicMessageSource.offer
    return topicDispatcher.onTopicMessage(message, context);
}
```

Zero-allocation, zero-copy: the borrowed tailer payload is consumed inline before
`offer` returns. This is the topic hot path.

### partitionedWorkers

`PartitionedWorkerExecutionPolicy.onTopicMessage` mirrors the service-message
partitioned path:

1. Generated ingress extracts the configured partition key for the topic from the
   borrowed `message.payloadSegment()` (§Partition keys). Extraction runs on the
   polling thread and must return a primitive `long` without allocation.
2. The worker index is `stableHash(key) % workerCount` (same hashing as service
   messages), so messages with the same key land on the same worker and preserve
   order.
3. The consumer thread copies the topic message (topic id, index, payload bytes)
   into the target worker's preallocated `PartitionedMessageSlot` — extended in
   phase 12/14 to carry topic identity alongside the copied payload — and returns
   from `offer`. The borrowed payload is released by the next poll of that
   subscription only after the copy completes.
4. The worker thread builds a `TopicContext` over the copied slot and calls
   `topicDispatcher.onContextTopicMessage(context)`.

Copy semantics are identical to partitioned service-message dispatch: one ingress
copy into worker-owned storage is required until a native payload retain/release
ABI exists. The framework must never drop a single message within a partition.

### virtualThreads

`VirtualThreadExecutionPolicy.onTopicMessage` mirrors its service path: ingress
decodes the payload into task-owned state (or copies the bytes) before submitting
bounded virtual-thread work; native order is not preserved after submission.
Topic messages that must be processed in order should use consumerThread or
partitionedWorkers.

## Partition keys

Topics have no built-in key; partitioning for topics is **application-declared**
on the handler (phase 14: `@RingloomTopicHandler(partitionKey = "...")`). The
generated extractor reads the field path directly from the borrowed payload
segment, exactly like generated SBE service-message extractors. The framework
shares the existing `GeneratedPartitionKeyExtractor` mechanism but keys it by
topic id for the topic path.

Generated topic partition-key extraction dispatches through a generated `switch`
on topic id rather than a boxed map lookup, matching the service-message rule.
A topic handler with no declared partition key is dispatched without
partitioning (consumerThread directly, or round-robin/broadcast under
partitioned mode — see phase 14 for the default; recommended: in partitioned
mode, a keyless topic handler routes to a single dedicated worker to preserve
order, since broadcast consumption implies per-subscriber ordering matters).

## Borrowed-payload contract

Topic handlers must obey the same contract as service handlers, plus the
tailer-specific lifetime:

1. `TopicContext.payloadSegment()` / `TopicMessage.payloadSegment()` is borrowed
   and valid only during the handler invocation (consumerThread) or until the
   poll callback returns (after the copy in partitioned/virtual modes).
2. Handlers must not retain the segment, `TopicMessage`, or `TopicContext` after
   return. Copy bytes into application storage if retention is needed.
3. The payload is released by the **next poll of the same subscription**; a later
   poll invalidates the previous borrow. This is the ringloom-queue tailer
   semantics surfaced through the binding (cross-repo spec §4.1).

The generated dispatcher must document this in handler javadoc, matching how the
service-message dispatcher documents the `RingloomMessage` borrow.

## Polling loop integration (phase 12 recap, with dispatch)

Phase 12's `pollTopics()` calls `topicMessageSource.offer(...)` per message. With
phase 13, that `offer` runs the full dispatch path:

```java
for (TopicPollState state : pollStates) {
    while (state.subscription.poll(state.result) == RingloomStatus.OK) {
        topicMessageSource.offer(
            state.subscription.topic(), state.topicId, state.result);
        if (++dispatched >= perSubscriptionPollLimit) break;
    }
}
```

- `TopicPollState` now also carries the resolved `topicId` (from the registration
  response) and a reference to the handler binding (topic name → handler).
- `topicMessageSource` is the per-thread source created at runtime start with the
  active `MessageExecutionPolicy` and `GeneratedTopicDispatcher`.

## Zero-allocation topic hot path

| Path | Zero allocations | Zero copy |
|---|---|---|
| consumerThread handler with flyweight/MemorySegment payload | Yes | Yes |
| partitionedWorkers handler with preallocated slots | Yes | No (one copy into the slot) |
| virtualThreads handler | No guarantee | No guarantee |

Rules (same as the service-message hot path):

1. Generated topic dispatch is a `switch` on topic id — no boxed keys, no per-message maps.
2. Reusable `TopicMessage` / `TopicContext` / flyweights per thread.
3. Partition extraction returns a primitive `long` without allocation.
4. Pre-create dispatchers, extractors, and contexts at startup.

## Testing

Unit tests:

1. `TopicMessage` / `TopicContext` update-from and accessor round-trips.
2. `GeneratedTopicDispatcher` keyed dispatch selects the right handler branch by
   topic id (use a hand-written test dispatcher).

Execution-mode integration tests (topics-enabled broker; one publisher + one
subscriber service):

1. **consumerThread:** publish N keyed messages; the subscriber handler receives
   them in global order.
2. **partitionedWorkers:** publish messages with a declared partition key; all
   messages for a given key are delivered to the same worker in order; no
   per-partition drops/reorders. A keyless topic handler preserves order via a
   single worker.
3. **virtualThreads:** messages are delivered (order not guaranteed across
   submissions).
4. Borrowed-payload lifetime: a handler that retains the segment sees it
   invalidated after the next poll (documented behavior); copying works.
5. Partition-key extraction reads the field path from the borrowed segment
   without allocation (allocation test).

Allocation tests:

1. consumerThread topic dispatch steady-state allocates nothing (reused
   `TopicMessage`/`TopicContext`/flyweights).
2. partitionedWorkers ingress copy into a preallocated slot allocates nothing
   beyond the documented single copy.

## Acceptance criteria

1. Topic messages dispatch through the configured `MessageExecutionPolicy` under
   all three modes, with partition ordering preserved where applicable.
2. The consumer-thread topic path is zero-allocation and zero-copy.
3. The partitioned-worker path does one ingress copy and never drops/reorders
   within a partition.
4. The borrowed-payload contract is honored and documented for handlers.
5. Topic dispatch keys on topic id via a generated switch, not reflection or map
   lookup.

## Dependencies

- Phase 12 (poll loop, `TopicPollResult`, `TopicPollState`).
- Phase 14 (`GeneratedTopicDispatcher` implementation + partition-key extractors
  generated from annotations).
- Cross-repo binding spec (`TopicPollResult.payloadSegment()` borrow lifetime).
