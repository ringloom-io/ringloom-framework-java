# Phase 16 — Replicated KV stores

## Status and audience

**Status:** Design, ready for implementation.
**Audience:** Engineers implementing KV store support in the Java framework.

This document is the contract for the Java framework integration. The native design is
[`ringloom/docs/kv-store-spec.md`](https://github.com/ringloom-io/ringloom/blob/main/docs/kv-store-spec.md)
(called the **native spec** below, with section references like "native §9.2"). Read native §1–§3,
§9, §10 and §11 before starting any task here.

The work is split into tasks **16A–16G** (§13). Each task lists its files, deliverables, tests, and
acceptance criteria. Agents should do the tasks in dependency order and must not widen a task's scope.
If a task finds that something in this document contradicts the code, the agent stops and records the
conflict in §15 instead of guessing.

---

## 1. Summary

RingLoom now provides named, replicated key-value stores. Each store has **one writer client**
(enforced by a fenced lease) and **many readers**. Readers look up keys in a local LMDB copy through
shared memory, with no broker round trip and no allocation. Writes go through the store's backing
persistent topic `__kv.<store>` and are replicated asynchronously to every KV-enabled broker.

The framework exposes this in two layers, following the same pattern as persistent topics (phases
12–15):

1. **Raw runtime API**: `runtime.kv()` returns `KvRuntime`, which hands out framework-managed
   `KvReaderStore` and `KvWriterStore` handles. They take `MemorySegment` keys and values, return status
   codes, and hide session management, threading rules, ack tracking, and lease state.
2. **Generated typed stores**: an interface annotated with `@RingloomKvStore` declares get, contains,
   forEach, put, delete, and clear methods with typed keys and serializer-encoded values. The annotation
   processor validates the methods and generates a proxy on top of layer 1. Primitive and `String` keys
   are encoded without boxing or allocation.

To support this, the Java bindings in `../ringloom/bindings/java` get targeted additions (task 16A),
including a few small C ABI accessors.

## 2. Goals and non-goals

### 2.1 Goals

1. Declare stores in source with annotations, generated code, and no runtime reflection, consistent
   with the rest of the framework.
2. **Zero-allocation hot paths** for: `get` into a flyweight or holder on framework-owned platform
   threads; `contains`; `put`, `delete`, and `clear` with primitive, `String`, or `MemorySegment` keys
   and zero-copy serializers; and `replicate_once` ack registration and completion.
3. Hot-path methods return status codes. Exceptions are reserved for startup, configuration, and
   programming errors.
4. Reads work in every message execution mode (`consumerThread`, `partitionedWorkers`,
   `virtualThreads`) and from application-owned threads.
5. Standby writers: a service can queue for a writer lease and is notified when it is granted or
   revoked.
6. Readers never block service startup unless a store is configured as `required`.
7. YAML configuration, Avaje bean registration, metrics, and health status are supported.

### 2.2 Non-goals for this phase

1. **Change notifications or watching a store.** The backing topic `__kv.<store>` is reserved and
   cannot be subscribed to (native §4.1). Applications that need change events must publish them on
   a separate topic.
2. Zero-copy *borrowed* reads (`view`). The binding keeps `view`, but the framework uses the copying
   `get` into reusable scratch memory, which is allocation-free and fast enough for typical values
   (native target is ≤300 ns p50). A borrowed-view profile can be added later (§14).
3. Read-your-writes helpers, transactions, range or prefix scans, and store deletion. The native layer
   does not support these (native §1.2).
4. Opening stores by name at runtime when they were not declared or configured. The raw API can open
   stores programmatically, but only during startup or through the control-thread command path (§6.6).
5. Spring or Micronaut integration.

---

## 3. Fit with the framework

| Framework rule | How KV follows it |
|---|---|
| Compile-time wiring, no reflection | `@RingloomKvStore` interfaces generate proxies and a `GeneratedKvStoreBinding`, registered through `GeneratedRingloomApplication.kvStores()`. |
| Status codes on hot paths | Every generated method that can fail for expected reasons returns `int`, except ergonomic `V get(K)`, which returns `null`. |
| Zero-allocation profile | Per-thread scratch state, codecs resolved at proxy construction, preallocated status and length holders, and pooled ack entries. |
| Control-thread ownership | Lease state, ack completion, reader status refresh, background opens, and writer state handlers all run on the control thread. |
| Optional cost | `ringloom.kv.enabled: false` (the default) means no KV classes are initialized beyond config records and nothing native is touched. |
| Code placement | Same choice as topics: annotations and runtime in `ringloom-framework-core`, code generation in `ringloom-framework-processor`, YAML in `ringloom-framework-yaml`, and beans in `ringloom-ioc-avaje`. No new artifact. |
| Low-level escape hatch | `KvReaderStore` and `KvWriterStore` expose raw segments. The native `io.ringloom.service.KvReader` and `KvWriter` can still be used directly. |

Package for all new core types: `io.ringloom.framework.kv` (sub-packages `kv.codec` and `kv.config`;
config records live in `io.ringloom.framework.config.kv` to match `config.topic`).

---

## 4. Developer model

### 4.1 Declaring a store (reader side)

```java
@RingloomKvStore(name = "prices", serializer = "sbe")
public interface PriceStore extends KvReaderOperations {

    /** Zero-alloc on platform threads: returns a flyweight valid until the next read of this store on this thread. */
    @RingloomKvGet
    PriceSnapshotDecoder get(long instrumentId);

    /** Status profile: OK, KV_MISSING, NOT_READY, SERIALIZATION_ERROR, ... */
    @RingloomKvGet
    int get(long instrumentId, KvValue<PriceSnapshotDecoder> out);

    @RingloomKvContains
    boolean contains(long instrumentId);

    /** Iterates one consistent snapshot; not zero-alloc for decoded keys of non-primitive types. */
    @RingloomKvForEach
    int forEach(KvEntryVisitor<PriceSnapshotDecoder> visitor);
}
```

### 4.2 Declaring a store (writer side)

```java
@RingloomKvStore(
        name = "prices",
        serializer = "sbe",
        maxKeyLength = 8,
        maxValueLength = 256,
        defaultTtlMillis = 0)
public interface PriceStoreWriter extends KvWriterOperations {

    @RingloomKvPut
    int put(long instrumentId, PriceSnapshot snapshot);

    @RingloomKvPut(ackMode = TopicAckMode.REPLICATE_ONCE)
    int putAcked(long instrumentId, PriceSnapshot snapshot, AckCallback callback, Object context);

    @RingloomKvPut
    int putExpiring(long instrumentId, PriceSnapshot snapshot, @RingloomKvTtl long ttlMillis);

    @RingloomKvDelete
    int delete(long instrumentId);

    @RingloomKvClear
    int clear();
}
```

A store interface can declare both read and write methods. One application can declare several
interfaces for the same store name; they share one native reader handle and one native writer handle
(§6.1).

### 4.3 Using stores

Generated store proxies are obtained like generated clients:

```java
PriceStore prices = runtime.generatedClient(PriceStore.class);   // or injected by Avaje
```

Batching uses the operations from `KvWriterOperations`:

```java
writer.beginBatch();
try {
    for (Quote q : quotes) writer.put(q.id(), q.snapshot());
} finally {
    int status = writer.commitBatch();
}
```

### 4.4 Writer lease state handlers

```java
@RingloomServiceComponent
public final class PricePublisherLifecycle {
    @RingloomKvWriterStateHandler(store = "prices")
    public void onWriterState(KvWriterStateEvent event) {
        if (event.state() == KvWriterState.GRANTED) { /* became the active writer */ }
    }
}
```

Handlers run on the control thread after native control polling returns, never inside a native
callback (§7.3). It is safe to call KV APIs from them.

---

## 5. Task 16A — Binding and C ABI changes (cross-repo, `../ringloom`)

The bindings in `ringloom` v0.4.0 work but have limits that block the framework. All changes are
**additive** except the session thread-confinement relaxation (§5.1), which loosens an existing check.
Work in `../ringloom/bindings/java` (and `include/ringloom_service.h` plus `src/service/c_abi.zig` for
§5.5). Bump the binding version (for example to `0.5.0`) and record that version in §15.

### 5.1 `KvSession`: serial-use instead of owner-thread confinement

**Problem:** `KvSession` throws `WrongThreadException` unless it is used by the thread that created
it. Virtual threads and session pools can't work with that, and sessions can't be closed during runtime
shutdown from the closing thread. The native session uses `MDB_NOTLS` (native §7.2), so it may be used by
any thread **as long as only one thread uses it at a time**.

**Change:**

1. Replace the `owner` check with an `AtomicInteger inUse` (or a `VarHandle` on an `int` field). Every
   public operation does `compareAndSet(0, 1)` on entry and sets it back to `0` in a `finally` block.
   If the CAS fails, throw `IllegalStateException("KvSession used concurrently")`.
2. Exception: while a `view` snapshot is held (between `view` and `release`), the session stays
   "pinned". Calls from another thread throw until `release`. Track this with a separate `pinnedBy`
   thread field that is set only while a view is held.
3. Switch `scratch` to a session-owned `Arena.ofShared()`. Keep the views arena confined, created
   lazily as today. Because of the pinning rule, a view can only be used by the pinning thread.
4. `close()` may be called from any thread when the session is not in use. If it is in use, throw
   `IllegalStateException`.
5. Keep a constructor flag or factory option `KvReader.newSession(KvSessionMode.CONFINED)` that keeps
   today's strict behavior. The default becomes `SERIAL`. Document both.

### 5.2 Offset and length overloads (no slicing on hot paths)

Slicing segments (`asSlice`) and building segments from addresses (`MemorySegment.ofAddress`) both
allocate. Native code takes `ptr + len`, so the binding passes raw addresses as `long` instead.

1. Add second downcall handles that take raw addresses as `JAVA_LONG` instead of `ADDRESS`, for
   `ringloom_kv_get`, `ringloom_kv_put`, and `ringloom_kv_delete` (`FunctionDescriptor` with `JAVA_LONG`
   in place of the key, value, and out pointers). On the supported 64-bit platforms a pointer has the
   same calling convention as `long`. Add a static check that `ADDRESS.byteSize() == 8`.
2. Public overloads:

```java
// KvSession
public int get(MemorySegment key, long keyOffset, long keyLength,
               MemorySegment destination, long destinationOffset, long destinationCapacity,
               long[] outLength);

// KvWriter
public int put(MemorySegment key, long keyOffset, long keyLength,
               MemorySegment value, long valueOffset, long valueLength,
               long ttlMillis, TopicAckMode ack, long[] outSequence);
public int delete(MemorySegment key, long keyOffset, long keyLength, TopicAckMode ack, long[] outSequence);
```

3. Check bounds with `Objects.checkFromIndexSize` (no allocation) and require native segments (as
   today). Use `segment.address() + offset` for the raw address.
4. The existing whole-segment methods delegate to the new overloads.

### 5.3 Reusable `forEach` upcall

**Problem:** `forEach` creates an `Arena`, an upcall stub, and a `Visit` object on every call, plus an
arena per visited entry.

**Change:**

1. Create one upcall stub per session at construction, bound to a per-session mutable `Visit` holder
   (`visitor`, `failure` fields). It is allocated in the session's shared arena.
2. `forEach(visitor)` sets `holder.visitor = visitor`, calls native, and then clears it.
3. Entry segments: build key and value views with `MemorySegment.ofAddress(addr).reinterpret(len)` and
   no arena (global scope, read-only). Document that they must not be used after the callback returns.
   This replaces the per-entry confined arena. It removes the safety net of an arena being closed, but
   it is allocation-light. Allocation of the two wrapper objects per entry is acceptable and documented
   (iteration is not a hot path).
4. Add a raw visitor variant for allocation-free iteration:

```java
@FunctionalInterface
public interface KvRawVisitor {
    /** Addresses are valid only during the callback. Return false to stop. */
    boolean visit(long keyAddress, long keyLength, long valueAddress, long valueLength);
}
public int forEachRaw(KvRawVisitor visitor);
```

### 5.4 `KvReader` additions

1. `KvStoreConfig config()`: the effective store config from the open response. The native reader
   already holds `response.config` (`Reader.openLocal(..., response.config, ...)`). Expose it through
   a new C ABI accessor (§5.5).
2. `int status(KvStatusHolder out)`: an allocation-free status read into a caller-owned, reusable
   holder backed by a preallocated 72-byte native segment. Keep `KvStatus status()` as the convenience
   form.
3. `KvReader.close()` keeps throwing if sessions are open. Add `int openSessionCount()` so the framework
   can log leaks.

### 5.5 C ABI additions (`include/ringloom_service.h`, KV ABI 6 → 7)

```c
/* Effective immutable store config captured at open. */
int ringloom_kv_reader_config(const ringloom_kv_reader_t*, ringloom_kv_store_config_t* out);

/* Highest acknowledged replicate_once sequence for this writer (monotonic). */
uint64_t ringloom_kv_writer_acked_seq(const ringloom_kv_writer_t*);
/* Topic leader epoch the writer currently publishes under (0 = none). */
uint64_t ringloom_kv_writer_leader_epoch(const ringloom_kv_writer_t*);
/* Current lease epoch (0 when not granted). */
uint64_t ringloom_kv_writer_epoch(const ringloom_kv_writer_t*);
/* Effective config the broker accepted (may differ from the request in map_size_hint only). */
int ringloom_kv_writer_config(const ringloom_kv_writer_t*, ringloom_kv_store_config_t* out);
```

Native implementation notes:

- `acked_seq` maps to `KvWriter.publisher.ackedSeq()` (an atomic acquire load). It uses the same
  sequence namespace as the `out_seq` that `put`, `delete`, `clear`, and `commit_batch` return
  (`publisher.lastAssignedToken()`).
- `leader_epoch` maps to `publisher.leader_epoch`, and `writer_epoch` maps to `KvWriter.epoch`. Read both
  under `ctx.lock()` the way `state()` does.
- Add C smoke tests to `src/service/kv/kv_c_smoke.c`.

Java side: `KvWriter.ackedSequence()`, `KvWriter.leaderEpoch()`, `KvWriter.writerEpoch()`, and
`KvWriter.config()`. The `long` accessors must be single `invokeExact` downcalls with no arena and no
allocation, and must not take the Java `synchronized` monitor, because they run on the control thread
and must not block behind a publishing thread (native code locks internally).

### 5.6 Heap segments (optional, recommended)

Link `ringloom_kv_get`, `ringloom_kv_put`, and `ringloom_kv_delete` (address forms as well) with
`Linker.Option.critical(true)` so that heap segments (`MemorySegment.ofArray`) are accepted.
`KvNative.pointer` then allows heap segments for these calls. The functions are short, never upcall,
and never block, which meets the requirements of `critical`. The framework uses this for virtual-thread
scratch and `byte[]` keys (§8.3). If this proves problematic, leave it out and record it in §15. The
framework then falls back to native scratch everywhere.

### 5.7 Test broker script

Add `--kv` to `scripts/start-test-broker.sh`. It implies `--topics` and writes
`broker.kv.enabled=true` and `broker.kv.path=<storage_dir>/kv`.

### 5.8 Clarify the `KvWriter` threading javadoc

The current javadoc says "Control polling must be serialized with writer operations and close." Native
`KvWriter` mutations and control updates are serialized by `KvContext.lock()`, so publishing may run
concurrently with `pollControl`. Only **`close()` must not race with `pollControl` or with publishing**.
Update the javadoc to say that. If verification shows otherwise, record it in §15 and make the framework
serialize writes with control polling instead (§7.1).

### 5.9 Tests (`KvBindingTest` and new tests)

1. A session created on thread A is used on thread B, then closed on thread C.
2. Concurrent use from two threads throws `IllegalStateException`. Use while pinned by `view` throws on
   another thread.
3. Offset overloads return the same results as slice-based calls. Out-of-bounds offsets throw.
4. `forEach` runs 1000 times with no per-call upcall-stub creation (assert via a stub-creation counter
   in a test hook, or an allocation measurement). `forEachRaw` allocates nothing.
5. `config()` on reader and writer matches the config the writer requested.
6. `ackedSequence()` reaches the sequence a `replicate_once` put returned (single-node broker).
7. Allocation test: steady-state `get` (offset form) and `put` (offset form) allocate 0 bytes per call,
   measured with `com.sun.management.ThreadMXBean#getThreadAllocatedBytes` over 100k iterations after
   warm-up.

**Acceptance:** binding published locally (`publishToMavenLocal`) with the new version; all KV binding
tests pass against a `--kv` test broker.

---

## 6. Task 16B — Core KV runtime (raw layer)

### 6.1 Ownership and lifecycle

`RingloomRuntime` owns an optional `KvRuntime`, created in `start()` when `config.kv().enabled()` is
true, after generated clients are created and **before** topics start (KV does not depend on framework
topics).

```java
public final class KvRuntime implements AutoCloseable {
    public KvReaderStore reader(String store);           // declared or configured store; throws IllegalArgumentException otherwise
    public KvWriterStore writer(String store);           // ditto
    public KvStoreStatus status(String store);           // snapshot for health endpoints; may allocate
    public Collection<String> stores();
    public CompletableFuture<KvReaderStore> openReader(String store, KvStoreRuntimeConfig settings);  // §6.6
    public CompletableFuture<KvWriterStore> openWriter(String store, KvStoreConfig config, KvStoreRuntimeConfig settings);
}
```

`RingloomRuntime.kv()` returns the `KvRuntime`, or throws `IllegalStateException("kv not enabled")`.
Generated bindings use it to get handles.

**Store registry:** at most one `KvReaderStore` and one `KvWriterStore` per store name, created from:

1. generated bindings (`GeneratedRingloomApplication.kvStores()`), merged by store name, and
2. `ringloom.kv.stores.<name>` YAML entries with `reader: true` or `writer: true` (for raw-API-only use).

**Native client for opening:** `openKvReader` and `openKvWriter` are methods on `RingloomClient`. KV
opens use one dedicated low-level client created with `service.createClient(config.service().name())`,
owned by `KvRuntime` and closed after all KV handles. Never reuse an application target client: its
lifetime and routing are unrelated.

### 6.2 Startup sequence

Inside `RingloomRuntime.start()`. Event loops are not running yet, so the calling thread may drive
native control polling. Native `waitStep` polls control itself in manual mode (`kv_context.poll_fn`).

1. For each **writer** store: open with `KvWriterOptions(acquire, openTimeoutMillis, maxFrameLength,
   stateHandler)`, where the state handler is the framework recorder (§7.3).
   - `HELD_BY_OTHER` with `acquire = FAIL_IF_HELD`: throw `RingloomKvException` (startup fails).
   - `QUEUED` (acquire `WAIT`): the handle opens in state `QUEUED`. Startup continues.
   - `CONFIG_MISMATCH`, `TOPIC_DISABLED`, timeouts, and other failures: throw. Writers are always
     required.
2. For each **reader** store:
   - `required: true`: open with `waitReadyTimeoutMillis` (default 5000). If it fails, throw.
   - `required: false` (default): try once with `waitReadyTimeoutMillis = 0`. If it succeeds, the store
     is `OPEN`; it may still return `NOT_READY` until its first generation is published (native
     `not_ready` with a store directory still opens). On `UNKNOWN_STORE`, `NOT_READY` without a
     directory, or a timeout, mark the store `PENDING` and schedule background retries (§6.6). On
     `KV_NOT_AVAILABLE_LOCALLY` or `KV_ENGINE_MISMATCH`, throw: these are deployment errors that retrying
     won't fix.
3. Read the effective config from the opened handles (`config()`, §5.4) and size per-thread scratch
   (§8).
4. Register the control-thread hooks (§7) and metrics (§10).

### 6.3 Control-thread hook (also fixes topic ack polling)

Today `ControlAgent.doWork()` calls only `service.pollControl(limit)`. `RingloomRuntime.pollControl()`
(external mode) also calls `topicRuntime.pollAckFeedback()`, but dedicated and shared modes never do.
**This is an existing bug: topic `replicate_once` acks don't complete in dedicated or shared mode.** KV
needs the same post-poll hook, so fix both here:

1. Add `ControlAgent(String roleName, RingloomService service, int pollLimit, Runnable afterPoll)`.
   `doWork()` returns `service.pollControl(limit) + afterPollWork`, where `afterPoll` is an
   `IntSupplier` that returns work done.
2. `RingloomRuntime` builds one `afterControlPoll()` that runs, in order:
   `topicRuntime.pollAckFeedback()` (if topics are on) and then `kvRuntime.afterControlPoll()` (if KV
   is on). Both the agent and `RingloomRuntime.pollControl()` call it.
3. Add a regression test showing that topic acks complete in `DEDICATED` mode.

`KvRuntime.afterControlPoll()` must not allocate in steady state. It does:

1. Dispatch any recorded writer state transitions (§7.3).
2. Poll ack progress for each writer with pending acks (§7.4).
3. Run due background-open commands (§6.6), at most one native open per call.
4. Refresh reader status into preallocated holders every `statusIntervalMillis` (§10).

### 6.4 `KvReaderStore` (raw read API)

```java
public final class KvReaderStore {
    public String name();
    public KvStoreState state();                 // PENDING, OPEN, FAILED, CLOSED (framework view)
    public boolean isReady();                    // OPEN and native state not INITIALIZING
    public KvStoreConfig config();               // null while PENDING

    /** Copying get. Returns OK, KV_MISSING, KV_BUFFER_TOO_SMALL (outLength[0] = required), NOT_READY. */
    public int get(MemorySegment key, long keyOffset, long keyLength,
                   MemorySegment destination, long destinationOffset, long capacity, long[] outLength);

    /** OK if present (any length, including 0), KV_MISSING, NOT_READY. */
    public int contains(MemorySegment key, long keyOffset, long keyLength);

    /** One snapshot; visitor rules as in the binding. Returns OK, NOT_READY, or a native error. */
    public int forEach(KvVisitor visitor);
    public int forEachRaw(KvRawVisitor visitor);

    public int status(KvStatusHolder out);       // allocation-free
}
```

- If the store is `PENDING`, every read returns `RingloomStatus.NOT_READY` immediately, with no
  allocation.
- `contains` is a `get` with zero capacity: `OK` (length 0) or `KV_BUFFER_TOO_SMALL` means present, and
  `KV_MISSING` means absent. It never copies a value.
- Every call acquires a session per §6.5 and releases it before returning.

### 6.5 Session management

**`KvSessionManager`** (one per reader store) hands out native `KvSession`s (`SERIAL` mode, §5.1):

1. **Platform threads:** a `ThreadLocal<KvThreadState>` (§8.1) caches a bound session per store. The
   first read on a thread creates it, if the per-store bound-session count is below
   `maxThreadSessions` (default 64). All bound sessions are also recorded in a store-level list so they
   can be closed at shutdown. Steady state: one `ThreadLocal.get()` and no CAS beyond the binding's
   in-use flag.
2. **Virtual threads, and platform threads over the cap:** borrow from a fixed pool of size
   `sessionPoolSize` (default `max(4, availableProcessors)`), created lazily up to that size and held in
   an Agrona `ManyToManyConcurrentArrayQueue<KvSession>`. If the pool is empty, the caller spins briefly
   (`Thread.onSpinWait()` × 64), then parks with `LockSupport.parkNanos(1_000)` in a loop. This is safe
   for virtual threads. A read never holds a session across a blocking call, so waits stay
   microseconds long.
3. A session is never shared between a `view` pin and a pooled borrow (the framework doesn't use
   `view`; §2.2).
4. Bound sessions of threads that died stay open until store close. This is a documented leak bounded
   by `maxThreadSessions`. Native `mdb_reader_check` only reclaims slots of dead *processes*.
5. Total native reader slots used per store is at most `maxThreadSessions + sessionPoolSize`. The
   default (68) is well below the broker default `broker.kv.max.readers = 1024`.
6. **Generation switches:** native sessions move to the new generation on the next `begin`. This is the
   only allowed read-path allocation (native §11.1). No framework action is needed.

### 6.6 Background opens and the control-thread command queue

Native opens may poll control internally, so **opens must never run concurrently with
`pollControl`**. Rules:

1. Before event loops start (inside `start()`), opens run inline on the starting thread.
2. Afterwards, every open (background retry or `KvRuntime.openReader/openWriter`) is enqueued as a
   `KvOpenCommand` on a `ManyToOneConcurrentArrayQueue` (capacity 64) and executed on the control
   thread inside `afterControlPoll()`. In `EXTERNAL` mode that is whatever thread calls
   `runtime.pollControl()`. The returned `CompletableFuture` completes on the control thread.
3. Background retry for `PENDING` readers: one attempt per store every `openRetryMillis` (default
   1000), using `waitReadyTimeoutMillis = 0`. A local broker answers in milliseconds, but each attempt
   can still block the control thread for one round trip. Log at INFO on the first pending state and at
   DEBUG on each retry. After success, publish the handle with a `volatile` write, after the session
   manager and config are fully built, so hot-path readers see a consistent store.
4. A retry that hits a non-retryable status (§6.2 step 2) moves the store to `FAILED` and logs at ERROR.
   Reads then return `NOT_READY` permanently, and `status()` reports `FAILED`.

### 6.7 `KvWriterStore` (raw write API)

```java
public final class KvWriterStore {
    public String name();
    public KvStoreConfig config();
    public KvWriterState state();        // GRANTED, QUEUED, REVOKED, REASSERTING, CLOSED (volatile, control-thread updated)
    public long writerEpoch();

    public int put(MemorySegment key, long keyOffset, long keyLength,
                   MemorySegment value, long valueOffset, long valueLength,
                   long ttlMillis /* KvTtl.STORE_DEFAULT = -1, KvTtl.NEVER = 0 */);
    public int putAck(MemorySegment key, long keyOffset, long keyLength,
                      MemorySegment value, long valueOffset, long valueLength,
                      long ttlMillis, AckCallback callback, Object context);
    public int delete(MemorySegment key, long keyOffset, long keyLength);
    public int deleteAck(MemorySegment key, long keyOffset, long keyLength, AckCallback callback, Object context);
    public int clear();
    public int clearAck(AckCallback callback, Object context);

    public int beginBatch();
    public int commitBatch();
    public int commitBatchAck(AckCallback callback, Object context);
}
```

Statuses are the native ones: `OK`, `BACKPRESSURE`, `KV_NOT_WRITER`, `TOPIC_LEADER_UNAVAILABLE`,
`KV_KEY_TOO_LARGE`, `KV_VALUE_TOO_LARGE`, `KV_BATCH_FULL`. Plus `RingloomHandlerStatus.SHUTDOWN` after
close. Callers must be able to rely on these numeric values. Confirm the native-to-Java mapping of
`leader_unavailable` during 16A and record it in §15.

### 6.8 Writer threading and batches

1. The binding's `KvWriter` methods are `synchronized`, and native code allows "one publishing thread
   per writer" in sequence. The framework adds a `ReentrantLock batchLock` per writer store:
   - `beginBatch()` acquires `batchLock` and keeps it until `commitBatch*()` or a failure path releases
     it. Calling `commitBatch` from a thread that doesn't hold the lock throws `IllegalStateException`.
   - Non-batch `put`, `delete`, and `clear` acquire `batchLock` for the duration of the call. They are
     reentrant inside the owner's batch, and other threads block until the batch commits. This prevents
     a mutation from another thread silently joining someone else's batch.
   - `ReentrantLock` is allocation-free in the uncontended steady state.
2. In batches, `put`, `delete`, and `clear` return `OK` with sequence 0. Ack callbacks can only be
   attached to `commitBatchAck`. Native auto-flushes inside a batch (buffer full) are not
   individually acknowledged. Document that a `commitBatchAck` ack covers the frames published by that
   commit only.
3. If `commitBatch` returns non-`OK` (for example `BACKPRESSURE`), the native batch stays buffered
   ("batches survive back-pressure unchanged"). The framework **keeps holding the lock**, so the caller
   can retry `commitBatch`. Add `abandonBatch()`, which releases the lock and clears the native batch by
   closing it (native has no abort; if it can't be supported, record in §15 and document that the caller
   must retry until `OK` or close the runtime).

### 6.9 Shutdown order

`RingloomRuntime.close()`, extended:

1. `generatedApplication.onRuntimeStopping`.
2. Stop message and control event loops (existing). **No more `pollControl` runs after this point.**
3. Close the message execution policy (existing; stops partitioned workers and the virtual-thread
   executor).
4. `kvRuntime.close()`:
   1. Mark every store `CLOSED`. New hot-path calls return `RingloomHandlerStatus.SHUTDOWN`.
   2. Fail queued `KvOpenCommand` futures.
   3. For each writer: complete pending acks with `AckStatus.SHUTDOWN`, release a held batch lock if the
      owner is gone (log WARN if a batch was buffered), then call `KvWriter.close()`. Native code appends
      `WRITER_RELEASE`, so a standby takes over.
   4. For each reader: close every bound and pooled session. If a session is in use, retry for up to
      `closeTimeoutMillis` (default 1000), then log WARN and skip it. Then close the `KvReader`, unless a
      session leaked, in which case log ERROR and skip `KvReader.close()` (it would throw).
   5. Close the KV low-level client.
5. Topic runtime, request registry, metrics, consumer, clients, and service (existing).

---

## 7. Task 16C — Writer lease state and acks

### 7.1 Types

```java
public enum KvWriterState { GRANTED, QUEUED, REVOKED, REASSERTING, CLOSED }

public record KvWriterStateEvent(String store, KvWriterState state, KvWriterState previous, long writerEpoch) {}
```

`KvWriterStateEvent` is allocated per transition. Transitions are rare control-plane events, so this is
acceptable.

### 7.2 Write gating

Native code already returns `KV_NOT_WRITER` when the writer is queued or revoked, and
`leader_unavailable` when it is reasserting. The framework does **not** add its own gating, to avoid
races with the native state. `KvWriterStore.state()` is informational.

### 7.3 State recording and dispatch

The binding's `KvStateHandler` runs **inside** native control polling (or inside an open's wait loop),
where `KvCallbackGuard` forbids KV, service, or client calls. So:

1. The framework's handler only records the transition. Each writer has a preallocated 16-slot ring of
   parallel arrays (`int[] states`, `long[] epochs`) and a `volatile long` write sequence. The read
   sequence is read only by the control thread. It does no allocation and no logging. The handler can
   run on the control thread or on a thread inside a startup open, but never on both at once (§6.6), so
   a single-producer ring is enough.
2. `afterControlPoll()` drains the ring on the control thread. For each transition, in order:
   1. update `KvWriterStore.state` and `writerEpoch` (volatile);
   2. apply the ack effects (§7.4);
   3. log at INFO (`kv writer 'prices' QUEUED -> GRANTED epoch=7`);
   4. invoke every generated `@RingloomKvWriterStateHandler` for that store
      (`GeneratedRingloomApplication.dispatchKvWriterState(event)`, §9.4). Exceptions are caught and
      logged at ERROR. They never escape into the control loop.
3. If the ring overflows (16 or more undrained transitions, which shouldn't happen), keep the newest
   state, set an `overflowed` flag, and log WARN on drain.

### 7.4 Acks

Reuse `io.ringloom.framework.topic.ack.TopicAckRegistry`, `AckCallback`, and `AckStatus` unchanged in
shape. The completion model is the same: a monotonic per-publisher sequence and an acked high-water
mark.

1. Each writer store owns one `TopicAckRegistry(maxPendingAcks, metrics, logger)`. `maxPendingAcks`
   defaults to 1024 and is configured per store.
2. `putAck`, `deleteAck`, `clearAck`, and `commitBatchAck` publish with `TopicAckMode.REPLICATE_ONCE`.
   On `OK` with sequence > 0, they call `registry.register(seq, writer.leaderEpoch(), callback, context,
   ackTimeoutNanos)`. If `register` returns false (pool full or shut down), complete the callback inline
   with `AckStatus.SHUTDOWN` and still return `OK`. The write was accepted; only tracking failed. This
   matches the topic publisher. On non-`OK`, do not register.
3. `afterControlPoll()`: for each writer with `pending > 0`, call
   `registry.advanceReplicatedCount(writer.leaderEpoch(), writer.ackedSequence())` and fire
   `onLeaderChanged(epoch)` when the epoch increases. This is the same logic as
   `TopicRuntime.pollAckFeedback()`. Extract a small shared helper
   `AckFeedbackPoller.poll(registry, epoch, acked)` in `topic.ack` and use it from both places.
4. Lease effects:
   - `→ REASSERTING`: no registry action. The new leader epoch isn't known yet, so don't guess it. Step
     3 sees the epoch increase once the writer retargets, and unacked frames from before the failover
     then complete as `LEADER_CHANGED`.
   - `→ REVOKED`: `registry.completeAll(AckStatus.REVOKED)`. **Add `REVOKED` to `AckStatus`**, with
     javadoc saying it is KV-only ("the writer lease was revoked; frames published under it are fenced
     and will not take effect").
5. Ack timeout sweeps: schedule `registry.sweepTimeouts` on `RingloomScheduler` exactly like
   `TopicRuntime.scheduleAckTimeoutSweep`. This only applies when `ackTimeoutMillis > 0`.

### 7.5 Tests

Unit tests use a fake writer facade: introduce a package-private `KvWriterPort` interface over the
binding `KvWriter`, so state and ack logic can be tested without a broker.

1. A recorded transition sequence `QUEUED → GRANTED → REASSERTING → GRANTED → REVOKED` dispatches in
   order, updates state, and invokes handlers once each.
2. A handler that throws doesn't stop dispatch or the control loop.
3. Acks complete `ACKED` when `ackedSequence` reaches the sequence. An epoch increase completes older
   entries with `LEADER_CHANGED`. `REVOKED` completes everything with `REVOKED`. Timeout yields
   `ACK_TIMEOUT`. Shutdown yields `SHUTDOWN`.
4. Steady-state `putAck` registration and completion allocate nothing (allocation test).
5. Regression for §6.3: topic acks complete in `DEDICATED` mode.

---

## 8. Task 16D — Key codecs and value encoding

### 8.1 Per-thread state

```java
final class KvThreadState {           // one per thread per KvRuntime, held in a ThreadLocal
    final MemorySegment keyScratch;   // capacity = max(maxKeyLength) across stores
    final MemorySegment valueScratch; // capacity = max(maxValueLength) across *read* and *write* stores
    final long[] outLength = new long[1];
    final EncodeContext encodeContext = new EncodeContext();
    final DecodeContext decodeContext = new DecodeContext();
    final KvSession[] boundSessions;  // indexed by reader store ordinal
}
```

- Platform threads: native scratch from one `Arena.ofShared()` owned by `KvRuntime`, closed at
  `close()`. Native memory per thread is about `maxKeyLength + maxValueLength`. Document this. The
  default value limit is about 64 KiB per thread, so tune `maxValueLength`.
- Virtual threads: heap scratch (`MemorySegment.ofArray(new byte[n])`), sized lazily and grown on
  demand, so the GC reclaims it with the virtual thread. This requires §5.6.
  **Fallback without §5.6:** virtual threads use a native scratch buffer paired with each pooled
  session. Because that buffer goes back to the pool when the call returns, a flyweight would dangle. In
  this fallback, `V get(K)` on a virtual thread always uses the `MessageDecoder` (copying decode), and
  flyweight-only value types return `null` with a decode error logged once.
- Writer sizing: the effective writer config is known after open. Reader sizing for `PENDING` stores is
  unknown at startup: size lazily on the first successful open, and grow `valueScratch` for threads
  created earlier on first use (one allocation per thread per growth).

### 8.2 Buffer length without slicing

To avoid `asSlice` allocations when decoding a value shorter than the scratch:

1. Add `MemorySegmentMessageBuffer.wrap(MemorySegment segment, long length)`. `length()` returns the
   limit, and reads past it throw `IndexOutOfBoundsException`. The existing `wrap(segment)` sets
   `length = segment.byteSize()`.
2. Add `DecodeContext.payloadLength()` and its setter. The KV proxy sets it before calling a
   `FlyweightDecoder`. Flyweight decoders receive the full scratch segment and must use
   `payloadLength()` (or their own self-describing length, as SBE does). Document this on
   `FlyweightDecoder.wrap`.
3. Update the SBE and Fory modules' decoders and flyweights to respect the length limit where they
   read `segment.byteSize()`. Audit both modules and list the touched classes in the PR.

### 8.3 `KvKeyCodec`

```java
public interface KvKeyCodec<K> {
    /** Encodes key into target at offset 0; returns bytes written, or -1 if it doesn't fit. No allocation for built-ins. */
    int encode(K key, MemorySegment target, long capacity);
    /** Decodes a key from an iteration callback. May allocate (e.g. String). */
    K decode(MemorySegment source, long offset, long length);
}
```

Built-in key encodings, selected at compile time from the key parameter type:

| Key type | Encoding | Zero-alloc encode |
|---|---|---|
| `long` | 8 bytes, big-endian, sign bit flipped (`key ^ Long.MIN_VALUE`) so engine byte order equals signed numeric order | Yes (generated inline; no codec object or boxing) |
| `int` | 4 bytes, big-endian, sign bit flipped | Yes (inline) |
| `String` | UTF-8, encoded by a hand-written loop (`KvKeyCodecs.encodeUtf8(String, MemorySegment, long)`), surrogate pairs handled; an unpaired surrogate returns `RingloomStatus.INVALID_ARGUMENT` | Yes |
| `MemorySegment` | used as-is (no copy) | Yes |
| `byte[]` | copied into `keyScratch` (or passed as a heap segment with §5.6) | Yes |
| any other type `T` | serializer named by `keySerializer` (`MessageEncoder<T>` into `keyScratch`) | Depends on serializer |

Key encoding is a **cross-language contract**: other bindings (C++, Node) reading the same store must
use the same bytes. Document the encodings in the `RingloomKvStore` javadoc and the architecture doc.
Readers and writers in different languages must agree. Also note that iteration order is
engine-defined (native §7.2), even though byte order makes `long` keys sort numerically on LMDB today.

A key longer than the store's `maxKeyLength` returns `KV_KEY_TOO_LARGE` without a native call. An empty
key is rejected natively (`KV_KEY_TOO_LARGE`) and the framework passes that through.

### 8.4 Value handling in generated proxies

Codec lookup happens **once, in the proxy constructor**: `serializers.encoder/decoder/flyweight(name,
type)` is stored in final fields. This is an improvement over the per-call lookup in the existing topic
publisher template. A missing codec fails runtime startup with a clear message.

**Write path** (`put`):

1. Encode the key into `keyScratch`. For `MemorySegment` keys, use the segment.
2. Value: if the type is `MemorySegment`, pass it through. Otherwise `len = encoder.encodedLength(v,
   ctx)`. If `len > maxValueLength`, return `KV_VALUE_TOO_LARGE`. Otherwise
   `ctx.buffer().wrap(valueScratch, len)`, `encoder.encode(...)`, then call the writer with offset and
   length.
3. Return the native status.

**Read path** (`get`), with `out = valueScratch`:

1. Encode the key. Call `reader.get(key, 0, keyLen, out, 0, capacity, outLength)`.
2. `KV_BUFFER_TOO_SMALL` should not happen, because capacity is at least the store's `maxValueLength`.
   If it does (a config race on a `PENDING` store), grow the scratch once and retry.
3. `OK`: decode with `decodeContext.payloadLength(len)`. Use a flyweight decoder if one is registered
   for `(serializer, type)`; otherwise use `MessageDecoder.decode(buffer.wrap(out, len), ctx)`.
4. **Flyweight lifetime:** a flyweight returned from `get` points into the calling thread's
   `valueScratch`. It is valid until the next read **of any store** on the same thread (scratch is
   shared across stores). Document this prominently. It mirrors the borrowed-payload rule for handlers.
   On virtual threads it is valid until the next KV read by that virtual thread.

### 8.5 Value holders and visitors

```java
/** Reusable result holder for status-profile gets. Not thread-safe; reuse per thread. */
public final class KvValue<V> {
    public V value();          // null unless the last get returned OK
    public int length();       // encoded length of the last OK value
    public int status();       // last status
}

@FunctionalInterface
public interface KvEntryVisitor<V> {
    /** keyBytes is a borrowed view valid only during the call. Return false to stop. */
    boolean visit(MemorySegment keyBytes, V value);
}
```

`forEach` gives the raw key bytes so it never boxes or allocates for keys. Applications decode them with
`KvKeyCodecs.decodeLong(segment)` and similar helpers (static, allocation-free for primitives). Values
are decoded per entry with the method's serializer. The flyweight is reused across entries and is valid
only inside the callback.

### 8.6 Tests

1. Round-trip and byte-order properties for `long` and `int` (including negatives, `MIN_VALUE`, and
   `MAX_VALUE`) and `String` (ASCII, BMP, supplementary, unpaired surrogate).
2. Golden byte vectors for each built-in key encoding (protects the cross-language contract).
3. Length-limited `MemorySegmentMessageBuffer` rejects reads past the limit.
4. Allocation tests for encoding all built-in keys and for get/put through the generated paths
   (16E covers the full proxy).

---

## 9. Task 16E — Annotations and code generation

### 9.1 Annotations (core, `RetentionPolicy.SOURCE`, package `io.ringloom.framework.annotation`)

```java
@Target(TYPE) public @interface RingloomKvStore {
    String name();                         // 1..64 bytes, [A-Za-z0-9._-]
    String serializer() default "";        // default value serializer for methods ("" = runtime default)
    String keySerializer() default "";     // required only for non-built-in key types
    // Writer config (KvStoreConfig). Ignored for read-only interfaces. -1 / "" = native default.
    int maxKeyLength() default -1;
    long maxValueLength() default -1;
    long defaultTtlMillis() default 0;
    long mapSizeHint() default -1;
    String rollScheme() default "";
}
@Target(METHOD) public @interface RingloomKvGet { String serializer() default ""; }
@Target(METHOD) public @interface RingloomKvContains {}
@Target(METHOD) public @interface RingloomKvForEach { String serializer() default ""; }
@Target(METHOD) public @interface RingloomKvPut {
    String serializer() default "";
    TopicAckMode ackMode() default TopicAckMode.FIRE_AND_FORGET;
    long ttlMillis() default -1;           // -1 = store default, 0 = never; overridden by a @RingloomKvTtl parameter
}
@Target(METHOD) public @interface RingloomKvDelete { TopicAckMode ackMode() default TopicAckMode.FIRE_AND_FORGET; }
@Target(METHOD) public @interface RingloomKvClear  { TopicAckMode ackMode() default TopicAckMode.FIRE_AND_FORGET; }
@Target(PARAMETER) public @interface RingloomKvTtl {}
@Target(METHOD) public @interface RingloomKvWriterStateHandler { String store(); }
```

`TopicAckMode` is the existing framework annotation enum used by `@RingloomTopicPublish`.

Operation interfaces (core, `io.ringloom.framework.kv`) that a store interface may extend. The generated
proxy implements them by delegating to the raw store:

```java
public interface KvReaderOperations {
    String storeName();
    boolean isReady();
    KvStoreStatus status();                // allocates; health endpoints only
}
public interface KvWriterOperations {
    String storeName();
    KvWriterState writerState();
    long writerEpoch();
    int beginBatch();
    int commitBatch();
    int commitBatchAck(AckCallback callback, Object context);
    void abandonBatch();
}
```

### 9.2 Accepted method shapes

`K` is the key parameter (§8.3), `V` is a value type with a codec for the method's serializer, `F` is a
type with a registered flyweight decoder, and `M` is `MemorySegment`.

| Annotation | Signature | Notes |
|---|---|---|
| `@RingloomKvGet` | `V get(K key)` | `null` on missing, expired, not ready, or decode error (decode error is logged at DEBUG and counted). Flyweight if available (§8.4). |
| `@RingloomKvGet` | `int get(K key, KvValue<V> out)` | Status profile. `SERIALIZATION_ERROR` on decode failure. |
| `@RingloomKvGet` | `int get(K key, M destination, long[] outLength)` | Raw copy into a caller segment. `KV_BUFFER_TOO_SMALL` per native. |
| `@RingloomKvContains` | `boolean contains(K key)` | `false` when not ready. |
| `@RingloomKvForEach` | `int forEach(KvEntryVisitor<V> visitor)` | |
| `@RingloomKvForEach` | `int forEach(KvRawVisitor visitor)` | Allocation-free. |
| `@RingloomKvPut` | `int put(K key, V value)` / `(K, M)` | Optional `@RingloomKvTtl long` parameter. |
| `@RingloomKvPut` (`REPLICATE_ONCE`) | `int put(K key, V value, AckCallback cb, Object ctx)` | The ack parameters must be the last two. `@RingloomKvTtl` may come before them. |
| `@RingloomKvDelete` | `int delete(K key)` (+ ack parameters if `REPLICATE_ONCE`) | |
| `@RingloomKvClear` | `int clear()` (+ ack parameters if `REPLICATE_ONCE`) | |
| `@RingloomKvWriterStateHandler` | `public void m(KvWriterStateEvent e)` on a `@RingloomServiceComponent` | |

### 9.3 Validation (`KvStoreValidator`, `KvWriterStateHandlerValidator`)

Fail compilation with a clear message on any of the following:

1. Annotation on a non-interface, or an interface method without exactly one KV annotation (default and
   static methods are ignored; methods inherited from `KvReaderOperations` and `KvWriterOperations` are
   allowed).
2. Invalid store name (charset or length).
3. A signature not listed in §9.2, a missing ack parameter with `REPLICATE_ONCE`, ack parameters with
   `FIRE_AND_FORGET`, or more than one `@RingloomKvTtl` parameter.
4. A non-built-in key type without `keySerializer`.
5. No serializer resolvable for a non-`MemorySegment` value: method, store, and runtime default are all
   empty. The runtime default is resolved at runtime, so the processor only warns when both the method
   and the store are empty, matching the existing behavior for topics.
6. Two interfaces in the same compilation with the same store name and **different writer config**
   values, or different `keySerializer`s or key types (keys must encode identically).
7. A `@RingloomKvWriterStateHandler` for a store that no interface writes, or a handler with the wrong
   signature or visibility.
8. A `maxKeyLength` greater than 511 (the LMDB limit, native §4.2) or less than 1. For long or int keys,
   a `maxKeyLength` smaller than 8 or 4 bytes.

### 9.4 Generated artifacts

1. `<Interface>_RingloomKvStore`: the proxy class. Templates go under
   `processor/templates/kv-store*.java.mustache`, with one template per method shape:
   `kv-get-value`, `kv-get-holder`, `kv-get-raw`, `kv-contains`, `kv-foreach`, `kv-foreach-raw`,
   `kv-put`, `kv-put-ack`, `kv-delete`, `kv-clear`. Built-in key encoding is emitted **inline** in each
   method (for example `keyScratch.set(BE_LONG, 0, key ^ Long.MIN_VALUE)`), so there is no codec dispatch
   and no boxing.
2. `GeneratedKvStoreBinding` (core SPI, `io.ringloom.framework.generated`):

```java
public interface GeneratedKvStoreBinding {
    Class<?> storeType();
    String store();
    boolean reads();
    boolean writes();
    io.ringloom.service.KvStoreConfig storeConfig();     // writer config with annotation overrides applied over defaults(); null when !writes()
    int keyLengthHint();                                 // from key type/maxKeyLength; for scratch sizing
    Object create(RingloomRuntime runtime, SerializerRegistry serializers);
}
```

3. Additions to `GeneratedRingloomApplication` (all `default` methods, so this is backward compatible):

```java
default List<GeneratedKvStoreBinding> kvStores() { return List.of(); }
default void dispatchKvWriterState(KvWriterStateEvent event) {}
default boolean requiresKvStores() { return !kvStores().isEmpty(); }
```

   `dispatchKvWriterState` is generated as a `switch (event.store())` over string constants that calls
   component methods. Strings in a switch don't allocate, and the event is already allocated (§7.1).
4. `RingloomRuntime.start()`: if `requiresKvStores()` and `!config.kv().enabled()`, throw
   `IllegalStateException("generated application declares KV stores but ringloom.kv.enabled is false")`.
5. After the KV runtime opens its stores, create each proxy and put it into the existing
   `generatedClients` map under `storeType()`, so `runtime.generatedClient(PriceStore.class)` works the
   same way it does for topic publishers.
6. Register the new annotations in `RingloomFrameworkProcessor.getSupportedAnnotationTypes()`, and add
   model classes `model/KvStore`, `model/KvMethod`, and `model/KvWriterStateHandler`, following the
   `TopicPublisher` and `TopicPublish` pattern.

### 9.5 Tests (processor module, compile-testing style used by existing processor tests)

1. Golden generated source for one interface that covers every method shape.
2. One negative compile test for each validation rule in §9.3.
3. Merged-store tests: a reader interface and a writer interface for the same name produce one binding
   each, and the runtime opens one native reader and one native writer.
4. Generated `dispatchKvWriterState` routes to the right component method.

---

## 10. Task 16F — Configuration, metrics, and Avaje

### 10.1 Config records (`io.ringloom.framework.config.kv`)

```java
public record KvRuntimeConfig(
        boolean enabled,                          // default false
        long statusIntervalMillis,                // default 1000; reader status refresh for metrics
        long openRetryMillis,                     // default 1000
        long closeTimeoutMillis,                  // default 1000
        Map<String, KvStoreRuntimeConfig> stores) {}

public record KvStoreRuntimeConfig(
        boolean reader,                           // open a reader even without generated read methods (raw API)
        boolean writer,                           // open a writer even without generated write methods (raw API)
        boolean required,                         // readers: block startup until ready (default false)
        long waitReadyTimeoutMillis,              // required readers; default 5000
        boolean prefault,                         // default false
        KvTtlClock ttlClock,                      // PRECISE (default) | COARSE
        int maxThreadSessions,                    // default 64
        int sessionPoolSize,                      // default max(4, cores)
        KvAcquireMode acquire,                    // FAIL_IF_HELD (default) | WAIT
        long openTimeoutMillis,                   // writer; default 5000
        long maxFrameLength,                      // writer; 0 = native default
        int maxPendingAcks,                       // default 1024
        long ackTimeoutMillis,                    // default 0 = no timeout
        KvStoreConfigOverride config) {}          // optional writer store-config overrides (raw-only stores, or ops tuning of mapSizeHint)
```

`RingloomApplicationConfig` gets a sixth component, `KvRuntimeConfig kv`, defaulting to
`KvRuntimeConfig.disabled()`. Keep the existing 5-argument constructor as a delegating secondary
constructor so callers in samples, Avaje, and tests keep compiling. Validate in the compact constructors:
positive intervals, pool sizes ≥ 1, and store names that pass the §9.3 rule 2 check.

Precedence for a writer `KvStoreConfig`: native `KvStoreConfig.defaults()`, then annotation values,
then YAML `config` overrides. Because the first creation wins and later different configs fail with
`CONFIG_MISMATCH` (`map_size_hint` excluded, native §4.2), log the effective config at INFO on writer
open.

### 10.2 YAML (`ringloom-framework-yaml`)

```yaml
ringloom:
  kv:
    enabled: true
    statusIntervalMillis: 1000
    openRetryMillis: 1000
    stores:
      prices:
        required: false
        prefault: true
        ttlClock: coarse            # precise | coarse
        maxThreadSessions: 64
        sessionPoolSize: 8
        acquire: wait               # failIfHeld | wait
        openTimeoutMillis: 5000
        maxPendingAcks: 4096
        ackTimeoutMillis: 2000
        config:
          mapSizeHint: 4294967296
      audit-cache:
        reader: true                # raw-API-only store
```

Follow the strict-loader style of `topics(...)`: `requireKeys` at every level, enum names in
lowerCamelCase as in existing YAML, and full key paths in error messages. Add `"kv"` to the allowed root
keys. Store names in YAML that no generated binding declares require `reader: true` or `writer: true`.
Otherwise the loader fails with "unknown kv store".

### 10.3 Metrics

Register them through `RuntimeRingloomMetrics.registerGauge` and `registerCounter` (native-backed
slots, visible to `ringloom-metrics-opentelemetry` with no change). Use names of the form
`kv.<store>.<metric>`:

| Metric | Kind | Source |
|---|---|---|
| `kv.<s>.reader.state` | gauge | native `KvStatus.state` (0–4), or 100+ for framework `PENDING`/`FAILED` |
| `kv.<s>.reader.lag_entries` | gauge | `KvStatus.lagEntries` |
| `kv.<s>.reader.heartbeat_age_ms` | gauge | `KvStatus.heartbeatAgeMillis` |
| `kv.<s>.reader.generation` | gauge | `KvStatus.generation` |
| `kv.<s>.reader.applied_index` | gauge | `KvStatus.appliedIndex` |
| `kv.<s>.reader.sessions` | gauge | bound + pooled sessions |
| `kv.<s>.reader.decode_errors` | counter | proxy decode failures |
| `kv.<s>.writer.state` | gauge | `KvWriterState.ordinal()` |
| `kv.<s>.writer.epoch` | gauge | lease epoch |
| `kv.<s>.writer.ack_pending` | gauge | registry |
| `kv.<s>.writer.transitions` | counter | §7.3 |

Refresh them on the control thread every `statusIntervalMillis` using the allocation-free
`KvReader.status(KvStatusHolder)` (§5.4). Writes into metric slots are VarHandle stores (existing).

`KvStoreStatus` (returned by `KvRuntime.status` and `KvReaderOperations.status`) is a record that
combines the framework state, the last native status snapshot, and writer state. It is intended for
health checks: a store is *healthy* when `OPEN`, the native state is `LIVE` or `CATCHING_UP`, and
`heartbeatAgeMillis < 3 × statusIntervalMillis`. Expose `boolean healthy()` with that rule documented.

### 10.4 Avaje (`ringloom-ioc-avaje`)

1. Register each `GeneratedKvStoreBinding.storeType()` as a bean, both eagerly and lazily, mirroring
   `registerGeneratedClients` and `registerLazyGeneratedClients`.
2. Register `KvRuntime` as a bean when KV is enabled.
3. Include KV writer state handler components in the `withComponents` path (they're
   `@RingloomServiceComponent`s, so this should work as is; add a test).
4. Note: topic publishers are not registered as Avaje beans today either. That is out of scope here,
   but note it in §15 as a follow-up.

### 10.5 Tests

YAML parse, defaults, and strict-key failures; config validation; Avaje bean resolution for a generated
store (both lazy and eager); and metric names present after start.

---

## 11. Task 16G — Integration tests, allocation tests, sample, docs

### 11.1 Integration tests (`ringloom-framework-core`, using `TestBroker` with `--kv`)

Extend `TestBroker` with `startKvEnabled(repoRoot, workspace)`. Scenarios on a single node:

1. A generated writer and reader in one runtime: put, get, delete, clear, contains, forEach, TTL expiry
   (put with 200 ms TTL, gone after 300 ms), and `replicate_once` ack reaches `ACKED`.
2. A reader started before any writer: startup succeeds, `get` returns `NOT_READY`/`null`, and after a
   writer runtime starts and puts, the reader becomes ready within `openRetryMillis + 1s` and sees the
   value.
3. `required: true` with no writer: startup fails after `waitReadyTimeoutMillis`.
4. Two writer runtimes: the second with `FAIL_IF_HELD` fails startup. With `WAIT`, it starts as
   `QUEUED`, receives a `GRANTED` state handler call after the first runtime closes, and then its puts
   succeed.
5. Reads from a `partitionedWorkers` handler, a `virtualThreads` handler (1000 concurrent virtual
   threads, `sessionPoolSize = 4`), and an application platform thread.
6. Batch: 1000 puts in one batch with a commit ack. Another thread's `put` blocks until commit.
7. Shutdown with sessions bound on dead threads: clean close, no native errors, and the writer lease is
   released (a waiting writer gets `GRANTED`).
8. `DEDICATED` mode topic ack regression (§6.3).

Multi-node scenarios (leader failover, reassert, partition heal) are covered by native e2e (native
§14.4). Don't duplicate them here.

### 11.2 Allocation tests

Same harness as the existing allocation-sensitive tests (`ThreadMXBean#getThreadAllocatedBytes`, after
a 50k-iteration warm-up, then 100k iterations, run on a platform thread), with an assertion of
**0 bytes**:

1. Generated `get` returning an SBE flyweight, with a `long` key.
2. Generated `get(K, KvValue<F>)` with a `String` key.
3. Generated `contains`.
4. Generated `put` with an SBE encoder and a `long` key, `FIRE_AND_FORGET`.
5. Generated `putAck` plus ack completion via `afterControlPoll`.
6. `forEachRaw` over 1000 entries.

### 11.3 Sample `samples/market-data-kv`

Use the existing SBE and Avaje style:

- `price-writer`: holds the `prices` writer lease (`acquire: wait`). Two instances are run to show
  standby. It updates `PriceSnapshot` entries on a schedule (`@RingloomSchedule`).
- `price-reader`: serves `@RingloomHandler` requests by reading `PriceStore.get(instrumentId)` (a
  flyweight) on `consumerThread` dispatch.
- `config/broker.properties` with `broker.topics.enabled=true` and `broker.kv.enabled=true`.
- A README covering prerequisites, run steps, killing the active writer, and watching standby
  takeover.

### 11.4 Docs

1. Add the "Replicated KV stores" section to `docs/architecture.md` (a draft is already in place; update
   it to match what was built).
2. README: a KV feature bullet and a short usage snippet.
3. Javadoc on every public KV type, including the flyweight lifetime rule, thread rules, and the key
   encoding contract.

---

## 12. Status code summary

| Status | Constant | Source | Meaning in the framework |
|---|---|---|---|
| 0 | `RingloomStatus.OK` | native | Success |
| 7 | `BACKPRESSURE` | native | Writer back-pressured; retry |
| 11 | `NOT_READY` | native/framework | Store pending, not built yet, or failed open |
| 12 | `TOPIC_LEADER_UNAVAILABLE` | native | Writer reasserting or no leader (confirm mapping in 16A) |
| 32 | `KV_MISSING` | native | Key absent or expired (status-profile `get` only) |
| 33 | `KV_BUFFER_TOO_SMALL` | native | Raw `get` destination too small |
| 34 | `KV_NOT_WRITER` | native | Lease not held (queued or revoked) |
| 38/39 | `KV_KEY_TOO_LARGE`/`KV_VALUE_TOO_LARGE` | native/framework | Limits exceeded |
| 40 | `KV_BATCH_FULL` | native | Single record exceeds the frame budget |
| 65537 | `RingloomHandlerStatus.SERIALIZATION_ERROR` | framework | Encode or decode failure |
| 65540 | `RingloomHandlerStatus.SHUTDOWN` | framework | Store closed |

---

## 13. Task plan

| Task | Title | Depends on | Repos and modules |
|---|---|---|---|
| **16A** | Binding and C ABI changes (§5) | — | `ringloom`: `bindings/java`, `include/ringloom_service.h`, `src/service/c_abi.zig`, `src/service/kv/*`, `scripts/start-test-broker.sh` |
| **16B** | Core KV runtime, raw API, sessions, opens, shutdown, control hook (§6) | 16A | core |
| **16C** | Writer lease state and acks (§7) | 16B | core |
| **16D** | Key codecs, scratch, length-limited buffers (§8) | 16A (can run in parallel with 16B) | core, serializer-sbe, serializer-fory |
| **16E** | Annotations and code generation (§9) | 16B, 16C, 16D | core (annotations, SPI), processor |
| **16F** | Config, YAML, metrics, Avaje (§10) | 16B (config records start in 16B as needed), 16E for Avaje beans | core, yaml, ioc-avaje |
| **16G** | Integration and allocation tests, sample, docs (§11) | all | core tests, samples, docs |

Per-task rules for agents:

1. Bump `ringloomJavaBindings` in `gradle/libs.versions.toml` as the first commit of 16B, after 16A is
   published to Maven Local.
2. Run `./gradlew spotlessApply check` before finishing a task. Integration tests need
   `-Dringloom.brokerBin=... -Dringloom.repoRoot=...` as the existing topic tests do.
3. Keep public API names exactly as written here. If a name has to change, update this document in the
   same change.
4. Use no reflection and no runtime annotation scanning anywhere in KV code (Avaje's existing lazy proxy
   path is the only exception and is reused, not extended).

---

## 14. Future work (designed not to be blocked)

1. **Borrowed-view profile:** a `@RingloomKvView` method that passes a borrowed segment to a callback
   while holding the session pinned. It needs an allocation-free view in the binding (raw address plus
   Agrona `UnsafeBuffer.wrap(address, length)` for SBE flyweights).
2. **Read-your-writes:** expose the native `write_index` and `applied_index`, and add `awaitApplied(seq)`
   for writers co-located with readers once native phase 2 exposes it.
3. **Change notifications:** if the native side ever allows subscribing to `__kv.*` topics read-only,
   or adds a change feed, map it to `@RingloomKvChangeHandler`.
4. Spring and Micronaut bean registration.

---

## 15. Open items and verification log

Agents record findings here as they go (date, task, finding, decision).

### 15.1 Resolved during implementation (2026-11-08/09)

1. **16A, status mapping:** a native `leader_unavailable` KV write maps to Java status **12,
   `TOPIC_LEADER_UNAVAILABLE`**. It is returned while the writer is reasserting, or when publishing
   reports not connected or failed. A rejected publisher registration maps to `KV_NOT_WRITER`. §12 is
   correct.
2. **16A, threading:** native writes, `state`, the epoch accessors and control polling all take
   `KvContext.lock()`, and `ackedSeq` is a lock-free atomic load. Publishing on one thread while
   another polls control is safe. `close_writer` must not race with publishing, because that is a
   use-after-free. The binding's `synchronized` methods prevent it, and the framework closes writers
   only after event loops stop (§6.9).
3. **16A, abandon batch:** native `abandonBatch` was added, exposed as `ringloom_kv_abandon_batch` in the
   C ABI and `KvWriter.abandonBatch()` in Java. Frames auto-flushed while the batch was open stay
   published.
4. **16A, heap segments (§5.6):** `Linker.Option.critical(true)` with heap access is applied to `get`
   only. Using it for `put`/`delete` risks a deadlock: native writes spin on `KvContext.lock`; the control
   thread holds that lock while calling the Java state callback, and a critical call blocks the GC
   safepoint that callback may need. Writes therefore require native segments, so per-thread KV
   scratch, including on virtual threads, is native memory from `Arena.ofAuto` (§8.1).
5. **16A, release:** shipped in ringloom **v0.5.0** (KV ABI 7) and published to Maven Central as
   `io.ringloom:ringloom-java-bindings:0.5.0`. The framework pins 0.5.0 in `gradle/libs.versions.toml`.
   `writerEpoch()` stays non-zero while `REASSERTING`, because the lease is still held.
6. **16B, writer open:** right after a service starts, the native open fails immediately with
   `TOPIC_LEADER_UNAVAILABLE`. `KvRuntime` retries until `openTimeoutMillis`, polling control in
   between. That takes about 3 s on a fresh service.
7. **16B, ack fixes that also apply to topics:**
   - `AckFeedbackPoller` now applies a leader change *before* advancing. With the old order the new
     epoch was recorded first, so `LEADER_CHANGED` never fired.
   - `TopicAckRegistry` rescans pending entries even when the acked count hasn't moved. Before, an ack
     registered after the count already covered it could hang until timeout.
   - `TopicAckRegistry.completePending(status)` completes pending acks on `REVOKED` without shutting the
     registry down.
   - `ControlAgent` now runs the post-poll hook, so topic acks complete in `DEDICATED` and `SHARED` modes
     (`DedicatedModeTopicAckIntegrationTest`).
8. **16B, behaviour notes:**
   - Sessions are cached per store through a `ThreadLocal`.
   - `putAck`/`deleteAck`/`clearAck` inside a batch throw `IllegalStateException`; use `commitBatchAck`.
   - The initial writer state is dispatched with `previous == null`.
   - If a batch lock is still held at close, the framework waits `closeTimeoutMillis`, logs WARN and
     closes the native writer anyway.
   - An unknown store surfaces as `TOPIC_UNKNOWN (15)` and is retried. `KV_NOT_AVAILABLE_LOCALLY`,
     `KV_ENGINE_MISMATCH`, `INVALID_ARGUMENT` and `TOPIC_DISABLED` are not retried.
9. **16D, key encoding:** `KvKeyCodecs.encodeUtf8` returns `-1` (`DOES_NOT_FIT`) or `-2`
   (`UNPAIRED_SURROGATE`). `RingloomStatus.INVALID_ARGUMENT` is `1` and would be ambiguous with "1 byte
   written". Generated proxies map these through `KvGeneratedSupport.keyStatus` to `KV_KEY_TOO_LARGE`
   and `INVALID_ARGUMENT`. `MemorySegmentMessageBuffer.segment()` still returns the whole segment, so
   code reading it directly must respect `length()`.
10. **16E, codegen:**
    - Generic key and value types are rejected, as are mixed key types within one interface and a
      misplaced `@RingloomKvTtl`.
    - Nested store interfaces get flattened proxy names, for example `Stores_QuoteStore_RingloomKvStore`.
    - KV SBE codecs are registered with template id `0` unless a client or handler already registers the
      type.
11. **16F, configuration and metrics:**
    - The "unknown kv store" rule is enforced in `KvRuntime.start()`, because the YAML loader cannot see
      generated bindings.
    - KV ack metrics are exposed as `kv.<store>.writer.ack_*` through a renaming metrics wrapper.
    - `KvStoreStatus.healthy()` compares the heartbeat age unsigned, so a native `-1` ("never beat") is
      not reported as healthy.
    - `KvRuntime` is an Avaje provider bean, available after `runtime.start()`.
12. **16G, allocation:**
    - `SbeDecodeContext.flyweight` and `SbeEncodeContext.codec` used a capturing
      `computeIfAbsent` lambda that allocated 16 B per call. They now use a plain get/put.
    - With that fix, generated `get` (flyweight, `long` key), `get(String, KvValue)`, `contains`, `put`
      (SBE), `putAck` with completion, and `forEachRaw` all measure **0 bytes** after warm-up
      (`samples/market-data-kv` `GeneratedKvStoreAllocationTest`).

### 15.2 Follow-ups

1. **Bindings:** `RingloomService.pollControl(int)` allocates a confined `Arena` on every call, so every
   control-loop iteration allocates in all runtime modes. Fix it in `ringloom/bindings/java` with a
   preallocated per-service out segment. The KV allocation tests subtract this measured baseline.
2. Add a name-prefix constructor to `TopicAckRegistry` and remove the KV metrics renaming wrapper.
3. Register generated topic publishers as Avaje beans. This gap already existed before this phase.
4. Not covered here: generated-store integration tests live in `samples/market-data-kv`, because
   `TestBroker` is in core's test sources and core tests don't run the annotation processor.
