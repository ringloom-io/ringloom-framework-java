# Phase 15 — Topic publisher runtime & ack registry

## Objective

Add the per-publisher pending-ack machinery that completes `AckCallback`s for
`replicate_once` publishes, driven by throttled high-water-mark feedback on the
control plane. This phase owns `AckCallback`, `TopicAckRegistry`, ack-status
enums, the HWM-advancement path on the control thread, and the leader-change /
shutdown completion paths.

This phase consumes:
- The low-level `TopicPublisher.publish(..., REPLICATE_ONCE, outIndex)` +
  `isAcked(index)` surface (cross-repo binding spec §3).
- The control-plane `TopicAckFeedback` / `TopicLeaderChanged` notifications
  surfaced through `TopicRuntime` hooks (phase 12).

It is consumed by the generated publisher binding from phase 14, which registers
callbacks here and reads completion via the callback.

This phase is the topic analogue of the request/response registry (phase 11):
both are pending-completion registries driven asynchronously from the control
plane. The key difference is the completion key (publish index vs correlation id)
and the completion driver (HWM feedback vs response message).

## Ack model recap (from the native design)

Ack mode is chosen **per publish**. For `replicate_once`:

- The leader assigns a publish `index` (the ringloom-queue index of the appended
  record) and returns it to the producer (binding `out_index`).
- The leader sends **throttled** `TopicAckFeedback{topic_id, leader_epoch,
  replicated_hwm}` frames toward brokers that have local producers for the topic.
  `replicated_hwm` is the highest master index applied by **≥1 replica**
  (multi-node), or the master append index on a single-node broker.
- The framework completes every pending callback whose assigned index is
  `<= replicated_hwm`.

There is **no per-message round trip**; one feedback frame can complete many
pending publishes. `fire_and_forget` publishes are never tracked. This is the
locked design from `topics-architecture.md` §8 and native spec 03 §6.

## Deliverables

1. `AckStatus` enum (completion status carried to callbacks).
2. `AckCallback` functional interface.
3. `TopicAckRegistry` — per-publisher pending-ack table keyed by publish index,
   with HWM completion, timeout, leader-change, and shutdown paths.
4. `TopicRuntime.onAckFeedback(...)` / `onTopicLeaderChanged(...)` wiring
   (implementing the hooks declared in phase 12).
5. Control-thread scheduling of timeout sweeps.
6. Tests for HWM completion, timeout, leader change, shutdown, and the
   zero-allocation hot path.

## AckStatus

```java
public enum AckStatus {
    /** The publish index was applied by >=1 replica (or appended on single-node). */
    ACKED,
    /** The ack did not complete before its timeout. */
    ACK_TIMEOUT,
    /** The topic leader changed; the publish will not be acked under this epoch. */
    LEADER_CHANGED,
    /** The runtime is shutting down; pending acks are not guaranteed durable. */
    SHUTDOWN;
}
```

Callbacks receive the status, the assigned publish index, and the caller's
opaque context. Acks never throw to the caller; failure is a status.

## AckCallback

A stateless functional interface, designed to be a caller-reused singleton (no
capturing lambdas on the hot path), mirroring `ResponseCallback` from phase 11:

```java
@FunctionalInterface
public interface AckCallback {
    /**
     * Invoked on the control thread when the publish index is acked, times out,
     * the leader changes, or the runtime shuts down. The status indicates which.
     *
     * @param publishIndex the index assigned by the leader for this publish
     * @param status the completion status
     * @param userContext the opaque context supplied at publish time
     */
    void onComplete(long publishIndex, AckStatus status, Object userContext);
}
```

The callback runs on the **control thread** (the thread that polls the control
plane and processes `TopicAckFeedback`). It must be short; long work should hand
off to an application executor. The borrowed publish payload is not available
here (the publish already completed encoding); only the index/status/context are
passed.

## TopicAckRegistry

One instance per publisher (owned by the generated publisher proxy from phase
14). It holds pending entries keyed by publish index and exposes the HWM the
control thread advances.

### Pending entry

```java
final class PendingAck {
    long publishIndex;
    long deadlineNanos;        // Long.MAX_VALUE when no timeout
    AckCallback callback;
    Object userContext;
    long leaderEpoch;          // epoch under which the publish was sent
    // pool linkage for reuse
}
```

Entries are pooled (per-publisher) so `replicate_once` registration allocates
nothing on the hot path, mirroring the pooled `PendingRequest` design from phase
11. Indexes are monotonic per topic, so a simple array-indexed or ordered
structure can be used; the registry must support "complete all entries with
index <= hwm" efficiently.

### API

```java
public final class TopicAckRegistry {
    public TopicAckRegistry(int maxPending, RingloomMetrics metrics, Logger logger);

    /**
     * Registers a pending ack. Called by the generated publisher proxy on the
     * publish thread before returning from publishAck(...). Returns false (and
     * completes the callback with SHUTDOWN) if the registry is closed.
     */
    public boolean register(long publishIndex, long leaderEpoch, AckCallback callback,
                            Object userContext, long timeoutNanos);

    /**
     * Advances the replicated high-water-mark for this publisher's topic and
     * completes every pending entry with index <= hwm whose leaderEpoch is
     * current. Called on the control thread from TopicRuntime.onAckFeedback.
     */
    public void advanceHwm(long leaderEpoch, long hwm);

    /**
     * Completes all pending entries under a superseded epoch with LEADER_CHANGED.
     * Called on the control thread from TopicRuntime.onTopicLeaderChanged.
     */
    public void onLeaderChanged(long newLeaderEpoch);

    /** Sweeps timed-out entries. Called periodically from the control thread. */
    public int sweepTimeouts(long nowNanos);

    /** Completes all pending entries with SHUTDOWN. Called during runtime close. */
    public void completeAll(AckStatus status);
}
```

### HWM completion semantics

`advanceHwm(epoch, hwm)`:

1. Ignore frames whose `epoch` is **older** than the highest epoch seen (stale
   feedback from a superseded leader). Track `seenEpoch`.
2. For the current epoch, complete every pending entry with `publishIndex <= hwm`
   and `leaderEpoch == epoch` with `ACKED`. Entries from an older epoch are left
   for `onLeaderChanged` (they should already have been completed LEADER_CHANGED,
   but this is defensive).
3. Completion runs callbacks inline on the control thread. Entries are returned
   to the pool only after the callback returns.

Because indexes are monotonic per topic, a pending set ordered by index lets
`advanceHwm` complete a prefix; an array-backed ring or an Agrona
`Long2ObjectHashMap` with a tracked min-pending index both work. The choice must
keep `register` and `advanceHwm` allocation-free in steady state.

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
  per-method). Acks that never time out are valid (the HWM will eventually
  complete them or a leader change will).

### Shutdown

`RingloomRuntime.close()` calls `topicRuntime.close()`, which calls
`registry.completeAll(SHUTDOWN)` for every publisher before closing the native
handles. No pending callback is left dangling.

## TopicRuntime control-plane wiring (implements phase 12 hooks)

Phase 12 declared `onAckFeedback(...)` / `onTopicLeaderChanged(...)` on
`TopicRuntime`; this phase implements them. They are called **on the control
thread** — the control loop already polls the control ring buffer where
`TopicAckFeedbackMsg` (native template 15) and `TopicLeaderChangedMsg` (template
13) arrive.

```java
// on the control thread
void onAckFeedback(long topicId, long leaderEpoch, long replicatedHwm) {
    TopicAckRegistry registry = publisherRegistriesByTopicId.get(topicId);
    if (registry != null) {
        registry.advanceHwm(leaderEpoch, replicatedHwm);
    }
}

void onTopicLeaderChanged(long topicId, short leaderNode, long newEpoch) {
    TopicAckRegistry registry = publisherRegistriesByTopicId.get(topicId);
    if (registry != null) {
        registry.onLeaderChanged(newEpoch);
    }
    // the native TopicPublisher handle re-targets leaderNode/newEpoch on its own
}
```

`publisherRegistriesByTopicId` is populated when publishers are registered at
startup (phase 14 binding creation), keyed by the `topic_id` returned in the
registration response.

Timeout sweeps are scheduled on the control thread (reusing `RingloomScheduler`
or a fixed cadence inside the control poll), since HWM advancement already runs
there and timeouts are low-frequency.

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
    acks.register(outIndex[0], currentEpoch(), callback, userContext, ackTimeoutNanos);
    return status;
}
```

Rules:

1. Register the callback **only after a successful publish** (status OK); on send
   failure return the status and do not register (the message was not accepted).
2. Use a per-thread reused `long[1]` for `outIndex` — no allocation.
3. `nextCorrelationId()` is a monotonic per-publisher counter; the correlation id
   is forwarded to the leader for diagnostics but the completion key is the
   **publish index**, not the correlation id.
4. `currentEpoch()` is the publisher's known leader epoch; updated on leader
   change.

## Zero-allocation hot path

| Operation | Allocation profile |
|---|---|
| `publishAck` registration (pooled entry) | Zero in steady state |
| `advanceHwm` completing N entries | Zero (callbacks invoked inline, entries pooled) |
| `sweepTimeouts` | Zero in steady state |
| `publishAck` ergonomic overloads (decoded payload, capturing lambda) | Not guaranteed — excluded from guarantees |

Rules (same as the request/response registry):

1. Pool `PendingAck` entries; never allocate on `register`.
2. No boxed keys; the registry keys on primitive `long` publish index.
3. Callbacks are caller-owned singletons; `userContext` is caller-owned pooled
   state. The framework never captures lambdas or allocates wrapper objects.
4. `advanceHwm`/`onLeaderChanged`/`sweepTimeouts` complete entries without
   allocating.

## Metrics

Each publisher registry exposes (via `RingloomMetrics`):

1. `topic.ack.hwm` — last advanced high-water-mark (gauge).
2. `topic.ack.pending` — current number of pending entries (gauge).
3. `topic.ack.completed` — total acked (counter).
4. `topic.ack.timeout` — total timed-out (counter).
5. `topic.ack.leader_changed` — total completed via leader change (counter).
6. `topic.ack.shutdown` — total completed via shutdown (counter).

These help tune `ackTimeoutMillis` and observe replication lag / failover impact.

## Testing

Unit tests (no broker):

1. `register` then `advanceHwm(hwm >= index)` completes the entry with `ACKED`;
   the entry is pooled/reused.
2. `advanceHwm` with an **older** epoch is ignored (stale feedback).
3. `advanceHwm` completes a prefix (multiple entries with index <= hwm) in one
   call; entries with index > hwm remain pending.
4. `onLeaderChanged` completes stale-epoch entries with `LEADER_CHANGED`; new
   `register` calls use the new epoch.
5. `sweepTimeouts` completes past-deadline entries with `ACK_TIMEOUT`; non-expired
   entries remain.
6. `completeAll(SHUTDOWN)` completes everything; subsequent `register` returns
   false / completes SHUTDOWN.
7. Steady-state `register` + `advanceHwm` allocate nothing (allocation test).

Integration tests (topics-enabled broker):

1. `replicate_once` publish returns OK; the callback fires with `ACKED` after the
   HWM advances (single-node broker: on leader append; multi-node: after a replica
   applies).
2. Leader change mid-stream completes un-acked publishes with `LEADER_CHANGED`;
   acked publishes are unaffected; subsequent publishes under the new epoch ack
   normally (bounded failover window per the native design).
3. A publisher with a configured `ackTimeoutMillis` sees `ACK_TIMEOUT` for a
   publish that never acks (e.g. leader unreachable).
4. Shutdown completes all pending callbacks with `SHUTDOWN`.
5. Throttled feedback: many publishes completed by a single HWM frame (verify the
   HWM-based, not per-message, completion model).

## Acceptance criteria

1. `replicate_once` publishes register a pooled pending entry keyed by publish
   index with zero hot-path allocation.
2. `advanceHwm` completes the matching pending prefix on the control thread;
   stale-epoch feedback is ignored.
3. Leader change, timeout, and shutdown each complete pending entries with the
   correct status and never leak entries.
4. Callbacks are caller-owned and invoked inline on the control thread; the
   framework allocates nothing in steady state.
5. Metrics expose HWM, pending count, and completion breakdown.

## Dependencies

- Cross-repo binding (`TopicPublisher.publish(..., REPLICATE_ONCE, outIndex)`,
  `isAcked`, control-plane `TopicAckFeedback`/`TopicLeaderChanged` notification
  surfacing).
- Phase 12 (`TopicRuntime` control hooks, publisher registry map).
- Phase 14 (generated publisher binding consumes `TopicAckRegistry`).
- Phase 11 design precedent (pooled pending registry, generation/epoch safety).
