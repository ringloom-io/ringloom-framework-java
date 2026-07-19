# Phase 15 — Topic publisher runtime & ack registry

## Objective

Add the per-publisher pending-ack machinery that completes `AckCallback`s for
`replicate_once` publishes, driven by throttled replicated-count feedback on the
control plane. This phase owns `AckCallback`, `TopicAckRegistry`, ack-status
enums, the replicated-count advancement path on the control thread, and the
leader-change / shutdown completion paths.

This phase consumes:
- The low-level `TopicPublisher.publish(..., REPLICATE_ONCE, outIndex)` +
  `isAcked(token)` + `replicatedCount()` + `leaderEpoch()` surface (cross-repo
  binding spec §3).
- The control-plane `TopicAckFeedback` / `TopicLeaderChanged` notifications
  surfaced through the binding's pollable accessors and consumed via
  `TopicRuntime.pollAckFeedback()` (phase 12).

It is consumed by the generated publisher binding from phase 14, which registers
callbacks here and reads completion via the callback.

This phase is the topic analogue of the request/response registry (phase 11):
both are pending-completion registries driven asynchronously from the control
plane. The key difference is the completion key (per-topic publish token vs
correlation id) and the completion driver (replicated-count feedback vs response
message).

## Ack token model (from the native design)

> **Critical reconciliation.** An earlier draft of this spec assumed the
> client-facing ack key was the broker's ringloom-queue *index* and that the
> driver was a `replicated_hwm` queue-index high-water mark. The verified native
> implementation (`src/service/topics/topic_publisher.zig`,
> `src/broker/topics/topic_store.zig`) uses a **per-topic monotonic publish
> token** as the client-facing key, and exposes **`replicated_count`** (not
> `replicated_hwm`) to clients. This section states the resulting model.

Ack mode is chosen **per publish**. For `replicate_once`:

- The leader assigns a **per-topic monotonic publish token** at offer time
  (`TopicPublisher.next_token`, starting at 1) and returns it to the producer in
  the binding's `out_index`. This is **not** the ringloom-queue index: the queue
  index resets across daily/cycle rollovers and is unsuitable as a stable
  client-comparable token.
- The leader sends **throttled** `TopicAckFeedback{topic_id, leader_epoch,
  replicated_hwm, replicated_count}` frames toward brokers that have local
  producers for the topic (default interval 200µs; delivered directly to a local
  producer's service-control channel — no UDP round-trip when co-located).
  - `replicated_hwm` is the highest **queue index** applied by ≥1 replica (or
    appended on a single-node broker). The native struct keeps it **for
    diagnostics only** — it resets across cycle rollovers.
  - `replicated_count` is the monotonic **count of this topic's publishes**
    replicated to ≥1 replica, in the *same namespace* as the per-topic publish
    token. This is the value clients use.
- The framework completes every pending callback whose assigned publish token is
  `<= replicated_count`. This mirrors the native
  `TopicPublisher.isAcked(token) = replicated_count >= token` predicate exactly.

There is **no per-message round trip**; one feedback frame can complete many
pending publishes. `fire_and_forget` publishes are never tracked. This is the
locked design from `topics-architecture.md` §8 and native spec 03 §6.

The binding surfaces this to the framework via pollable accessors
(`TopicPublisher.replicatedCount()`, `TopicPublisher.leaderEpoch()`), not via a
native upcall. The framework's control thread polls them each tick
(`TopicRuntime.pollAckFeedback()`) and feeds the registry — the same zero-alloc
poll model as `isAcked`.

## Deliverables

1. `AckStatus` enum (completion status carried to callbacks).
2. `AckCallback` functional interface.
3. `TopicAckRegistry` — per-publisher pending-ack table keyed by the per-topic
   publish token, with replicated-count completion, timeout, leader-change, and
   shutdown paths.
4. `TopicRuntime.pollAckFeedback()` — control-thread polling of
   `TopicPublisher.replicatedCount()` / `leaderEpoch()` driving the registry.
5. Control-thread scheduling of timeout sweeps.
6. Tests for replicated-count completion, timeout, leader change, shutdown, and
   the zero-allocation hot path.

## AckStatus

```java
public enum AckStatus {
    /** The publish token was applied by >=1 replica (or appended on single-node). */
    ACKED,
    /** The ack did not complete before its timeout. */
    ACK_TIMEOUT,
    /** The topic leader changed; the publish will not be acked under this epoch. */
    LEADER_CHANGED,
    /** The runtime is shutting down; pending acks are not guaranteed durable. */
    SHUTDOWN;
}
```

Callbacks receive the status, the assigned publish token, and the caller's
opaque context. Acks never throw to the caller; failure is a status.

## AckCallback

A stateless functional interface, designed to be a caller-reused singleton (no
capturing lambdas on the hot path), mirroring `ResponseCallback` from phase 11:

```java
@FunctionalInterface
public interface AckCallback {
    /**
     * Invoked on the control thread when the publish token is acked, times out,
     * the leader changes, or the runtime shuts down. The status indicates which.
     *
     * @param publishIndex the per-topic publish token assigned by the leader
     * @param status the completion status
     * @param userContext the opaque context supplied at publish time
     */
    void onComplete(long publishIndex, AckStatus status, Object userContext);
}
```

The callback runs on the **control thread** (the thread that polls the control
plane and processes `TopicAckFeedback`). It must be short; long work should hand
off to an application executor. The borrowed publish payload is not available
here (the publish already completed encoding); only the token/status/context are
passed.

## TopicAckRegistry

One instance per publisher (owned by the generated publisher proxy from phase
14). It holds pending entries keyed by the per-topic publish token and exposes
the replicated count the control thread advances.

### Pending entry

```java
final class PendingAck {
    long publishIndex;         // the per-topic publish token (outIndex[0])
    long deadlineNanos;        // 0 (or sentinel) when no timeout
    AckCallback callback;
    Object userContext;
    long leaderEpoch;          // epoch under which the publish was sent
    // pool linkage for reuse
}
```

Entries are pooled (per-publisher) so `replicate_once` registration allocates
nothing on the hot path, mirroring the pooled `PendingRequest` design from phase
11. Publish tokens are monotonic per topic, so a simple array-indexed or ordered
structure can be used; the registry must support "complete all entries with
token <= replicatedCount" efficiently.

### API

```java
public final class TopicAckRegistry {
    public TopicAckRegistry(int maxPending, RingloomMetrics metrics, Logger logger);

    /**
     * Registers a pending ack. Called by the generated publisher proxy on the
     * publish thread before returning from publishAck(...). Returns false (and
     * leaves the callback unregistered — the proxy then completes it with
     * SHUTDOWN) if the registry is shut down or the pool is exhausted.
     */
    public boolean register(long publishIndex, long leaderEpoch, AckCallback callback,
                            Object userContext, long timeoutNanos);

    /**
     * Advances the replicated publish count for this publisher's topic and
     * completes every pending entry with token <= replicatedCount whose
     * leaderEpoch is current. Called on the control thread from
     * TopicRuntime.pollAckFeedback, fed by TopicPublisher.replicatedCount().
     */
    public void advanceReplicatedCount(long leaderEpoch, long replicatedCount);

    /**
     * Completes all pending entries under a superseded epoch with LEADER_CHANGED.
     * Called on the control thread from TopicRuntime.pollAckFeedback when the
     * polled leaderEpoch advances.
     */
    public void onLeaderChanged(long newLeaderEpoch);

    /** Sweeps timed-out entries. Called periodically from the control thread. */
    public int sweepTimeouts(long nowNanos);

    /** Completes all pending entries with SHUTDOWN. Called during runtime close. */
    public void completeAll(AckStatus status);
}
```

### Replicated-count completion semantics

`advanceReplicatedCount(epoch, replicatedCount)`:

1. Ignore frames whose `epoch` is **older** than the highest epoch seen (stale
   feedback from a superseded leader). Track `seenEpoch`.
2. For the current epoch, complete every pending entry with
   `publishIndex <= replicatedCount` and `leaderEpoch == epoch` with `ACKED`.
   Entries from an older epoch are left for `onLeaderChanged` (they should
   already have been completed LEADER_CHANGED, but this is defensive).
3. Completion runs callbacks inline on the control thread. Entries are returned
   to the pool only after the callback returns.

Because publish tokens are monotonic per topic, a pending set ordered by token
lets `advanceReplicatedCount` complete a prefix; an array-backed ring or an
Agrona `Long2ObjectHashMap` with a tracked min-pending token both work. The
choice must keep `register` and `advanceReplicatedCount` allocation-free in
steady state.

> The method is named `advanceReplicatedCount` (not `advanceHwm`) deliberately:
> the value is the per-topic replicated publish **count**, not a queue-index
> high-water mark. The broker tracks a queue-index HWM internally but does not
   expose it to clients because it resets across cycle rollovers.

### Leader change

`onTopicLeaderChanged(topicId, leaderNode, newEpoch)` in `TopicRuntime` routes to
each affected publisher's `registry.onLeaderChanged(newEpoch)`:

1. Complete every pending entry whose `leaderEpoch < newEpoch` with
   `LEADER_CHANGED`. (These publishes were sent under a stale leader; per the
   native design the un-acked tail beyond the most-advanced replica may be lost,
   and the catch-up barrier prevents truncating acked data — but a publish that
   was not yet acked is not guaranteed.)
2. Bump `seenEpoch` so subsequent stale-epoch feedback is ignored.

Producers re-target the new leader and stamp subsequent sends with `newEpoch`
(delegated to the native handle via `TopicPublisher` leader-change update). New
`register` calls use the new epoch.

### Timeout

- `register(..., timeoutNanos)` stores `deadlineNanos = now + timeoutNanos`
  (`Long.MAX_VALUE` if no timeout configured for the publisher).
- `sweepTimeouts(nowNanos)` (called from the control thread, e.g. via the
  scheduler agent or folded into the control poll) completes entries past their
  deadline with `ACK_TIMEOUT` and returns them to the pool.
- Timeout is **opt-in** per publisher (`publisherDefaults.ackTimeoutMillis` /
  per-method). Acks that never time out are valid (the replicated count will
  eventually complete them or a leader change will).

### Shutdown

`RingloomRuntime.close()` calls `topicRuntime.close()`, which calls
`registry.completeAll(SHUTDOWN)` for every publisher before closing the native
handles. No pending callback is left dangling.

## TopicRuntime control-plane wiring (pollable accessors)

Phase 12 declared `onAckFeedback(...)` / `onTopicLeaderChanged(...)` hooks on
`TopicRuntime`. The resolved binding does **not** deliver these via a native
upcall; it exposes pollable accessors `TopicPublisher.replicatedCount()` and
`TopicPublisher.leaderEpoch()` (binding spec §3.2, Option A). The framework's
control thread polls them each tick via `TopicRuntime.pollAckFeedback()` and
drives the registries — the same zero-alloc poll model as `isAcked`.

`pollAckFeedback()` runs **on the control thread** (alongside the control loop's
`service.pollControl(...)` call). The broker's `TopicAckFeedbackMsg` (native
template 15) and `TopicLeaderChangedMsg` (template 13) are processed by the
native control agent, which updates the publisher's `replicated_count` /
`leader_epoch` fields in place; the accessors read those fields.

```java
// on the control thread
void pollAckFeedback() {
    for (Map.Entry<Long, TopicPublisher> e : publishersByTopicId.entrySet()) {
        TopicPublisher publisher = e.getValue();
        TopicAckRegistry registry = publisherRegistriesByTopicId.get(e.getKey());
        if (registry == null) continue;
        long epoch = publisher.leaderEpoch();           // binding accessor
        long replicatedCount = publisher.replicatedCount(); // binding accessor
        long knownEpoch = registry.knownEpoch();
        registry.advanceReplicatedCount(epoch, replicatedCount);
        if (epoch > knownEpoch && epoch > 0) {
            registry.onLeaderChanged(epoch);
        }
    }
}
```

`publishersByTopicId` and `publisherRegistriesByTopicId` are populated when
publishers are registered at startup (phase 14 binding creation), keyed by the
`topic_id` returned by `TopicPublisher.topicId()`.

Timeout sweeps are scheduled on the control thread via `RingloomScheduler`
(`TopicRuntime.scheduleAckTimeoutSweep(scheduler)` registers a fixed-rate task),
since replicated-count advancement already runs there and timeouts are
low-frequency.

## Generated publisher integration (phase 14 recap, completed here)

The generated `publishAck(...)` method:

```java
public int publishAck(Quote quote, AckCallback callback, Object userContext) {
    // ... encode quote ...
    long[] outIndex = this.outIndexHolder;          // per-thread reused long[1]
    int status = handle.publish(segment, TopicAckMode.REPLICATE_ONCE, nextCorrelationId(), outIndex);
    if (status != RingloomStatus.OK) {
        return status;                              // callback not registered on send failure
    }
    long epoch = handle.leaderEpoch();              // stamp the live epoch at send time
    acks.register(outIndex[0], epoch, callback, userContext, ackTimeoutNanos);
    return status;
}
```

`outIndex[0]` is the per-topic publish token (native `next_token`), not the
queue index. The completion key is this token; `advanceReplicatedCount` completes
it when `replicated_count >= token` (mirroring the native `isAcked`).

Rules:

1. Register the callback **only after a successful publish** (status OK); on send
   failure return the status and do not register (the message was not accepted).
2. Use a per-thread reused `long[1]` for `outIndex` — no allocation.
3. `nextCorrelationId()` is a monotonic per-publisher counter; the correlation id
   is forwarded to the leader for diagnostics but the completion key is the
   **per-topic publish token**, not the correlation id.
4. `epoch` is read from `handle.leaderEpoch()` at send time so the registered
   entry is stamped with the epoch under which it was sent; `advanceReplicatedCount`
   then filters completions by epoch (stale-epoch entries are left for
   `onLeaderChanged`, which fires when the polled epoch advances).

## Zero-allocation hot path

| Operation | Allocation profile |
|---|---|
| `publishAck` registration (pooled entry) | Zero in steady state |
| `advanceReplicatedCount` completing N entries | Zero (callbacks invoked inline, entries pooled) |
| `sweepTimeouts` | Zero in steady state |
| `publishAck` ergonomic overloads (decoded payload, capturing lambda) | Not guaranteed — excluded from guarantees |

Rules (same as the request/response registry):

1. Pool `PendingAck` entries; never allocate on `register`.
2. No boxed keys; the registry keys on primitive `long` publish token.
3. Callbacks are caller-owned singletons; `userContext` is caller-owned pooled
   state. The framework never captures lambdas or allocates wrapper objects.
4. `advanceReplicatedCount`/`onLeaderChanged`/`sweepTimeouts` complete entries
   without allocating.

## Metrics

Each publisher registry exposes (via `RingloomMetrics`):

1. `topic.ack.replicated_count` — last advanced replicated publish count (gauge).
2. `topic.ack.pending` — current number of pending entries (gauge).
3. `topic.ack.completed` — total acked (counter).
4. `topic.ack.timeout` — total timed-out (counter).
5. `topic.ack.leader_changed` — total completed via leader change (counter).
6. `topic.ack.shutdown` — total completed via shutdown (counter).

These help tune `ackTimeoutMillis` and observe replication lag / failover impact.

## Latency characteristics

The ack-completion path is low-latency and zero-alloc:

- **Java accessor**: `TopicPublisher.replicatedCount()` is a single FFM
  `invokeExact` downcall returning a `long` — no arena, no allocation.
- **Native accessor**: `ringloom_topic_publisher_replicated_count` is a plain
  struct field read after a pointer null-check.
- **Broker feedback throttling**: the broker emits `TopicAckFeedback` at most
  every `broker.topics.ack_feedback_interval_us` (default 200µs) and delivers it
  directly to a local producer's service-control channel (no UDP round-trip when
  co-located).
- **Framework poll cadence**: `pollAckFeedback()` runs every control tick. The
  completion-latency floor is therefore the broker's feedback interval (200µs by
  default), not the framework. Tune the interval down to reduce it.

## Testing

Unit tests (no broker):

1. `register` then `advanceReplicatedCount(count >= token)` completes the entry
   with `ACKED`; the entry is pooled/reused.
2. `advanceReplicatedCount` with an **older** epoch is ignored (stale feedback).
3. `advanceReplicatedCount` completes a prefix (multiple entries with
   token <= count) in one call; entries with token > count remain pending.
4. `onLeaderChanged` completes stale-epoch entries with `LEADER_CHANGED`; new
   `register` calls use the new epoch.
5. `sweepTimeouts` completes past-deadline entries with `ACK_TIMEOUT`; non-expired
   entries remain.
6. `completeAll(SHUTDOWN)` completes everything; subsequent `register` returns
   false / completes SHUTDOWN.
7. Steady-state `register` + `advanceReplicatedCount` allocate nothing
   (allocation test).

Integration tests (topics-enabled broker):

1. `replicate_once` publish returns OK with a real `out_index` (per-topic token,
   starting at 1); the callback fires with `ACKED` once the polled
   `replicatedCount()` reaches the token (single-node broker: on leader append;
   multi-node: after a replica applies).
2. Leader change mid-stream completes un-acked publishes with `LEADER_CHANGED`;
   acked publishes are unaffected; subsequent publishes under the new epoch ack
   normally (bounded failover window per the native design).
3. A publisher with a configured `ackTimeoutMillis` sees `ACK_TIMEOUT` for a
   publish that never acks (e.g. leader unreachable).
4. Shutdown completes all pending callbacks with `SHUTDOWN`.
5. Throttled feedback: many publishes completed by a single feedback frame
   (verify the replicated-count-based, not per-message, completion model).

## Acceptance criteria

1. `replicate_once` publishes register a pooled pending entry keyed by the
   per-topic publish token with zero hot-path allocation.
2. `advanceReplicatedCount` completes the matching pending prefix on the control
   thread; stale-epoch feedback is ignored.
3. Leader change, timeout, and shutdown each complete pending entries with the
   correct status and never leak entries.
4. Callbacks are caller-owned and invoked inline on the control thread; the
   framework allocates nothing in steady state.
5. Metrics expose replicated count, pending count, and completion breakdown.

## Dependencies

- Cross-repo binding (`TopicPublisher.publish(..., REPLICATE_ONCE, outIndex)`,
  `isAcked`, control-plane `TopicAckFeedback`/`TopicLeaderChanged` notification
  surfacing).
- Phase 12 (`TopicRuntime` control hooks, publisher registry map).
- Phase 14 (generated publisher binding consumes `TopicAckRegistry`).
- Phase 11 design precedent (pooled pending registry, generation/epoch safety).
