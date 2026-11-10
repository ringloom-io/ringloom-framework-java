// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import io.ringloom.framework.config.kv.KvRuntimeConfig;
import io.ringloom.framework.config.kv.KvStoreRuntimeConfig;
import io.ringloom.framework.metrics.RingloomMetrics;
import io.ringloom.framework.scheduler.RingloomScheduler;
import io.ringloom.service.KvReader;
import io.ringloom.service.KvReaderOptions;
import io.ringloom.service.KvStoreConfig;
import io.ringloom.service.KvWriter;
import io.ringloom.service.KvWriterOptions;
import io.ringloom.service.RingloomClient;
import io.ringloom.service.RingloomException;
import io.ringloom.service.RingloomService;
import io.ringloom.service.RingloomStatus;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.slf4j.Logger;

/**
 * Owns the framework's replicated KV stores: one {@link KvReaderStore} and/or one
 * {@link KvWriterStore} per store name, a dedicated low-level client used for opens, and the
 * control-thread work (writer state dispatch, ack feedback, background opens, status refresh).
 *
 * <p>Lifecycle (driven by {@code RingloomRuntime}): construct, declare stores
 * ({@link #declareReader}/{@link #declareWriter}; stores configured in YAML with {@code reader: true}
 * or {@code writer: true} are declared automatically), {@link #start()} (opens inline on the starting
 * thread), then {@link #afterControlPoll()} after every native control poll, and finally
 * {@link #close()} after event loops have stopped.
 *
 * <p>Opens never run concurrently with native control polling: before {@link #start()} returns they
 * run inline; afterwards {@link #openReader}/{@link #openWriter} and background retries of pending
 * readers are queued and executed on the control thread inside {@link #afterControlPoll()}, at most
 * one native open per call.
 */
public final class KvRuntime implements AutoCloseable {
    private static final int COMMAND_QUEUE_CAPACITY = 64;
    private static final int LEADER_WAIT_POLL_LIMIT = 256;
    private static final long LEADER_WAIT_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final KvReaderStore[] NO_READERS = new KvReaderStore[0];
    private static final KvWriterStore[] NO_WRITERS = new KvWriterStore[0];
    /** Initial per-thread value scratch when no store config is known yet. */
    private static final long DEFAULT_VALUE_SCRATCH_BYTES = 4096;
    /** Upper bound of the initial per-thread value scratch; larger values grow the scratch on demand. */
    private static final long MAX_INITIAL_VALUE_SCRATCH_BYTES = 1 << 20;

    private final RingloomService service;
    private final String clientName;
    private final KvRuntimeConfig config;
    private final RingloomMetrics metrics;
    private final Logger logger;
    private final Consumer<KvWriterStateEvent> stateSink;
    private final Consumer<KvWriterStateEvent> stateDispatcher = this::dispatchWriterState;
    private final CopyOnWriteArrayList<Consumer<KvWriterStateEvent>> stateListeners = new CopyOnWriteArrayList<>();
    private final Map<String, KvReaderStore> readers = new ConcurrentHashMap<>();
    private final Map<String, KvWriterStore> writers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Map.Entry<String, CompletableFuture<KvWriterStore>>> pendingWriterFutures =
            new CopyOnWriteArrayList<>();
    /**
     * Guards the pre-start phase: declarations, pre-start opens and {@link #start()} itself, so a
     * store cannot be declared after start has taken its snapshot of the declared stores.
     */
    private final Object startLock = new Object();

    private final ManyToOneConcurrentArrayQueue<KvOpenCommand> commands =
            new ManyToOneConcurrentArrayQueue<>(COMMAND_QUEUE_CAPACITY);
    private final long statusIntervalNanos;
    private final long openRetryNanos;
    private volatile KvReaderStore[] readerArray = NO_READERS;
    private volatile KvWriterStore[] writerArray = NO_WRITERS;
    private volatile boolean started;
    private volatile boolean closed;
    private volatile long valueScratchBytes = DEFAULT_VALUE_SCRATCH_BYTES;
    private final ThreadLocal<KvThreadState> threadStates =
            ThreadLocal.withInitial(() -> new KvThreadState(valueScratchBytes));
    private RingloomClient client;
    private long defaultMaxReaders = 1;
    private long defaultMaxFrameLength;
    private long nextStatusRefreshNanos;
    private int retryCursor;

    /**
     * Creates the KV runtime. No native KV call is made until {@link #start()}.
     *
     * @param service    the started native service (used to create the dedicated KV client)
     * @param clientName the target name for the dedicated KV client (the service's own name)
     * @param config     the KV runtime configuration
     * @param metrics    the runtime metrics
     * @param logger     the runtime logger
     * @param stateSink  receives every writer state transition on the control thread (generated
     *                   {@code @RingloomKvWriterStateHandler} dispatch); may be {@code null}
     */
    public KvRuntime(
            RingloomService service,
            String clientName,
            KvRuntimeConfig config,
            RingloomMetrics metrics,
            Logger logger,
            Consumer<KvWriterStateEvent> stateSink) {
        this.service = Objects.requireNonNull(service, "service");
        this.clientName = Objects.requireNonNull(clientName, "clientName");
        this.config = Objects.requireNonNull(config, "config");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.logger = logger;
        this.stateSink = stateSink;
        this.statusIntervalNanos = TimeUnit.MILLISECONDS.toNanos(config.statusIntervalMillis());
        this.openRetryNanos = TimeUnit.MILLISECONDS.toNanos(config.openRetryMillis());
        for (Map.Entry<String, KvStoreRuntimeConfig> entry : config.stores().entrySet()) {
            if (entry.getValue().reader()) {
                declareReader(entry.getKey(), entry.getValue());
            }
            if (entry.getValue().writer()) {
                declareWriter(entry.getKey(), null, entry.getValue());
            }
        }
    }

    // ---- declaration (before start) --------------------------------------------------------------

    /**
     * Declares a reader store with its configured settings ({@code ringloom.kv.stores.<name>}, or
     * defaults). Idempotent per store name. Must be called before {@link #start()}.
     *
     * @param store the store name
     * @return the reader handle (opened by {@link #start()})
     */
    public KvReaderStore declareReader(String store) {
        return declareReader(store, config.store(store));
    }

    /**
     * Declares a reader store with explicit settings. Idempotent per store name (the first settings
     * win). Must be called before {@link #start()}.
     *
     * @param store    the store name
     * @param settings the per-store settings
     * @return the reader handle (opened by {@link #start()})
     */
    public KvReaderStore declareReader(String store, KvStoreRuntimeConfig settings) {
        synchronized (startLock) {
            ensureDeclarable(store);
            Objects.requireNonNull(settings, "settings");
            KvReaderStore reader = readers.computeIfAbsent(store, name -> newReaderStore(name, settings));
            rebuildReaderArray();
            return reader;
        }
    }

    /**
     * Declares a writer store with its configured settings. The effective store config is
     * {@code storeConfig} (or native defaults when {@code null}) with the YAML {@code config}
     * overrides applied. Must be called before {@link #start()}.
     *
     * @param store       the store name
     * @param storeConfig the writer store config (native defaults with annotation values applied), or
     *                    {@code null} for native defaults
     * @return the writer handle (opened by {@link #start()})
     */
    public KvWriterStore declareWriter(String store, KvStoreConfig storeConfig) {
        return declareWriter(store, storeConfig, config.store(store));
    }

    /**
     * Declares a writer store with explicit settings. Declaring the same store again with an equal (or
     * {@code null}) config returns the existing handle; a different config fails.
     *
     * @param store       the store name
     * @param storeConfig the writer store config before YAML overrides, or {@code null} for native defaults
     * @param settings    the per-store settings
     * @return the writer handle (opened by {@link #start()})
     */
    public KvWriterStore declareWriter(String store, KvStoreConfig storeConfig, KvStoreRuntimeConfig settings) {
        synchronized (startLock) {
            return declareWriterLocked(store, storeConfig, settings);
        }
    }

    private KvWriterStore declareWriterLocked(String store, KvStoreConfig storeConfig, KvStoreRuntimeConfig settings) {
        ensureDeclarable(store);
        Objects.requireNonNull(settings, "settings");
        KvStoreConfig effective = storeConfig == null ? null : settings.config().applyTo(storeConfig);
        KvWriterStore existing = writers.get(store);
        if (existing != null) {
            KvStoreConfig current = existing.requestedConfig();
            if (effective != null && current != null && !effective.equals(current)) {
                throw new IllegalStateException("kv store '" + store + "' declared with conflicting writer configs: "
                        + current + " vs " + effective);
            }
            if (effective != null && current == null) {
                existing.requestConfig(effective);
            }
            return existing;
        }
        KvWriterStore writer = newWriterStore(store, effective, settings);
        writers.put(store, writer);
        rebuildWriterArray();
        return writer;
    }

    private KvReaderStore newReaderStore(String store, KvStoreRuntimeConfig settings) {
        KvReaderStore reader = new KvReaderStore(store, settings);
        reader.attachMetrics(new KvStoreMetrics.Reader(metrics, store));
        return reader;
    }

    private KvWriterStore newWriterStore(String store, KvStoreConfig effective, KvStoreRuntimeConfig settings) {
        return new KvWriterStore(store, effective, settings, metrics, logger, stateDispatcher);
    }

    private void ensureDeclarable(String store) {
        if (!KvRuntimeConfig.isValidStoreName(store)) {
            throw new IllegalArgumentException("invalid kv store name '" + store + "'");
        }
        if (started) {
            throw new IllegalStateException("kv stores must be declared before start; use openReader/openWriter");
        }
        if (closed) {
            throw new IllegalStateException("kv runtime closed");
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    /**
     * Creates the dedicated KV client and opens every declared store inline on the calling thread
     * (writers first, then readers). Must run before event loops start.
     *
     * <p>Futures returned by pre-start {@link #openReader}/{@link #openWriter} calls complete only after
     * every store has been opened and the runtime is marked started, so their continuations may call
     * {@link #openReader}/{@link #openWriter} again (those opens are queued for the control thread).
     *
     * @throws RingloomKvException when a writer cannot be opened, a required reader is not ready in
     *     time, or a reader fails with a non-retryable deployment error
     */
    public void start() {
        synchronized (startLock) {
            if (started) {
                return;
            }
            if (closed) {
                throw new IllegalStateException("kv runtime closed");
            }
            startLocked();
        }
        completeStartupWaiters();
    }

    private void startLocked() {
        for (String configured : config.stores().keySet()) {
            if (!readers.containsKey(configured) && !writers.containsKey(configured)) {
                throw new IllegalArgumentException("unknown kv store ringloom.kv.stores." + configured
                        + ": no generated @RingloomKvStore declares it; set reader: true or writer: true"
                        + " to open it for raw-API use");
            }
        }
        KvReaderOptions readerDefaults = KvReaderOptions.defaults();
        defaultMaxReaders = readerDefaults.maxReaders();
        defaultMaxFrameLength = KvWriterOptions.defaults().maxFrameLength();
        client = service.createClient(clientName);
        for (KvWriterStore writer : writerArray) {
            if (writer.requestedConfig() == null) {
                writer.requestConfig(writer.settings().config().applyTo(KvStoreConfig.defaults()));
            }
        }
        for (KvWriterStore writer : writerArray) {
            openWriterNow(writer);
        }
        for (KvReaderStore reader : readerArray) {
            reader.holdWaiters();
            openReaderNow(reader, true);
        }
        for (KvReaderStore reader : readerArray) {
            reader.refreshStatus();
        }
        for (KvWriterStore writer : writerArray) {
            writer.refreshMetrics();
        }
        nextStatusRefreshNanos = System.nanoTime() + statusIntervalNanos;
        started = true;
    }

    /**
     * Completes the futures of pre-start opens. Runs after {@code started} is set and outside the
     * start lock, so continuations see a started runtime and their own opens take the queued path.
     */
    private void completeStartupWaiters() {
        for (KvReaderStore reader : readerArray) {
            reader.releaseWaiters();
        }
        for (Map.Entry<String, CompletableFuture<KvWriterStore>> waiting : pendingWriterFutures) {
            pendingWriterFutures.remove(waiting);
            KvWriterStore writer = writers.get(waiting.getKey());
            if (writer != null) {
                waiting.getValue().complete(writer);
            } else {
                waiting.getValue()
                        .completeExceptionally(
                                new IllegalStateException("kv writer '" + waiting.getKey() + "' was not opened"));
            }
        }
    }

    /**
     * Control-thread hook run after every native control poll. Allocation-free in steady state.
     * Dispatches writer state transitions, polls ack feedback, runs at most one queued or background
     * open, and refreshes reader status every {@code statusIntervalMillis}.
     *
     * @return the amount of work done
     */
    public int afterControlPoll() {
        if (!started || closed) {
            return 0;
        }
        int work = 0;
        KvWriterStore[] ws = writerArray;
        for (KvWriterStore writer : ws) {
            work += writer.afterControlPoll();
        }
        long now = System.nanoTime();
        KvOpenCommand command = commands.poll();
        if (command != null) {
            work++;
            execute(command);
        } else {
            work += retryOnePendingReader(now);
        }
        if (now - nextStatusRefreshNanos >= 0) {
            nextStatusRefreshNanos = now + statusIntervalNanos;
            for (KvReaderStore reader : readerArray) {
                work += reader.refreshStatus();
            }
            for (KvWriterStore writer : writerArray) {
                work += writer.refreshMetrics();
            }
        }
        return work;
    }

    /**
     * Schedules the ack-timeout sweep for writers with {@code ackTimeoutMillis > 0} on the control
     * thread scheduler (every 100 ms), like {@code TopicRuntime.scheduleAckTimeoutSweep}.
     *
     * <p>Scheduled unconditionally: writers opened later via {@link #openWriter} may enable ack
     * timeouts even when no startup store does. Writers without a timeout return immediately.
     *
     * @param scheduler the runtime scheduler
     */
    public void scheduleAckTimeoutSweep(RingloomScheduler scheduler) {
        if (scheduler == null) {
            return;
        }
        scheduler.scheduleAtFixedRate(0, 100, TimeUnit.MILLISECONDS, runtime -> sweepAckTimeouts(System.nanoTime()));
    }

    int sweepAckTimeouts(long nowNanos) {
        int swept = 0;
        for (KvWriterStore writer : writerArray) {
            swept += writer.sweepAckTimeouts(nowNanos);
        }
        return swept;
    }

    /**
     * Closes every store (writers first, releasing their leases), fails queued opens, and closes the
     * dedicated client. Must run after the event loops stopped (no concurrent control polling).
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        KvOpenCommand command;
        while ((command = commands.poll()) != null) {
            command.fail(new IllegalStateException("kv runtime closed"));
        }
        for (Map.Entry<String, CompletableFuture<KvWriterStore>> waiting : pendingWriterFutures) {
            waiting.getValue().completeExceptionally(new IllegalStateException("kv runtime closed"));
        }
        pendingWriterFutures.clear();
        for (KvReaderStore reader : readerArray) {
            // Mark readers closed first so hot-path calls return SHUTDOWN while writers close.
            reader.markClosing();
        }
        for (KvWriterStore writer : writerArray) {
            try {
                writer.close(config.closeTimeoutMillis());
            } catch (RuntimeException ex) {
                warn("kv writer '" + writer.storeName() + "': close failed", ex);
            }
        }
        for (KvReaderStore reader : readerArray) {
            try {
                reader.close(config.closeTimeoutMillis(), logger);
            } catch (RuntimeException ex) {
                warn("kv reader '" + reader.name() + "': close failed", ex);
            }
        }
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ex) {
                warn("kv client close failed", ex);
            }
            client = null;
        }
    }

    // ---- per-thread scratch (generated proxies) ---------------------------------------------------

    /**
     * Returns the calling thread's KV scratch state, creating it on first use. Used by generated store
     * proxies; see {@link KvThreadState} for the memory and flyweight lifetime rules.
     *
     * @return the thread state
     */
    public KvThreadState threadState() {
        return threadStates.get();
    }

    /**
     * Records a value a generated proxy failed to decode: counts it on the reader and logs at DEBUG.
     *
     * @param reader the reader store
     * @param error  the decode failure
     */
    public void recordDecodeError(KvReaderStore reader, Throwable error) {
        reader.recordDecodeError();
        if (logger != null && logger.isDebugEnabled()) {
            logger.debug("kv store '{}': value decode failed", reader.name(), error);
        }
    }

    /** Sizes the value scratch of threads created from now on (existing threads grow on demand). */
    private void noteValueLength(KvStoreConfig storeConfig) {
        if (storeConfig == null) {
            return;
        }
        long wanted = Math.min(storeConfig.maxValueLength(), MAX_INITIAL_VALUE_SCRATCH_BYTES);
        if (wanted > valueScratchBytes) {
            valueScratchBytes = wanted;
        }
    }

    // ---- public accessors ------------------------------------------------------------------------

    /**
     * Returns the reader handle of a declared or configured store.
     *
     * @param store the store name
     * @return the reader handle
     * @throws IllegalArgumentException when no reader is declared for the store
     */
    public KvReaderStore reader(String store) {
        KvReaderStore reader = readers.get(store);
        if (reader == null) {
            throw new IllegalArgumentException("no kv reader declared for store '" + store + "'");
        }
        return reader;
    }

    /**
     * Returns the writer handle of a declared or configured store.
     *
     * @param store the store name
     * @return the writer handle
     * @throws IllegalArgumentException when no writer is declared for the store
     */
    public KvWriterStore writer(String store) {
        KvWriterStore writer = writers.get(store);
        if (writer == null) {
            throw new IllegalArgumentException("no kv writer declared for store '" + store + "'");
        }
        return writer;
    }

    /**
     * Returns a health snapshot of a store (allocates; for health endpoints).
     *
     * @param store the store name
     * @return the status snapshot
     * @throws IllegalArgumentException when the store is unknown
     */
    public KvStoreStatus status(String store) {
        KvReaderStore reader = readers.get(store);
        KvWriterStore writer = writers.get(store);
        if (reader == null && writer == null) {
            throw new IllegalArgumentException("unknown kv store '" + store + "'");
        }
        return new KvStoreStatus(
                store,
                reader == null ? null : reader.state(),
                reader == null ? null : reader.nativeStatus(),
                writer == null ? null : writer.state(),
                writer == null ? 0 : writer.writerEpoch(),
                config.statusIntervalMillis());
    }

    /**
     * Returns the names of all declared stores (readers and writers), sorted.
     *
     * @return the store names
     */
    public Collection<String> stores() {
        TreeSet<String> names = new TreeSet<>(readers.keySet());
        names.addAll(writers.keySet());
        return Collections.unmodifiableSet(names);
    }

    /**
     * Returns the KV runtime configuration.
     *
     * @return the configuration
     */
    public KvRuntimeConfig config() {
        return config;
    }

    /**
     * Adds a listener for writer state transitions. Listeners run on the control thread after the
     * generated state handlers; exceptions are logged and never escape into the control loop.
     *
     * @param listener the listener
     */
    public void addWriterStateListener(Consumer<KvWriterStateEvent> listener) {
        stateListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Opens a reader store programmatically. Before {@link #start()} the store is declared and the
     * future completes once start has finished (if the store is open by then; otherwise after
     * background retries). Afterwards the open is queued and runs on the control
     * thread; the future completes there once the store is open (possibly after background retries),
     * or exceptionally when the open fails with a non-retryable error or the runtime closes.
     *
     * @param store    the store name
     * @param settings the per-store settings
     * @return a future completed with the open reader handle
     */
    public CompletableFuture<KvReaderStore> openReader(String store, KvStoreRuntimeConfig settings) {
        Objects.requireNonNull(settings, "settings");
        CompletableFuture<KvReaderStore> future = new CompletableFuture<>();
        if (!KvRuntimeConfig.isValidStoreName(store)) {
            future.completeExceptionally(new IllegalArgumentException("invalid kv store name '" + store + "'"));
            return future;
        }
        if (closed) {
            future.completeExceptionally(new IllegalStateException("kv runtime closed"));
            return future;
        }
        if (!started) {
            synchronized (startLock) {
                if (!started) {
                    try {
                        declareReader(store, settings).addWaiter(future);
                    } catch (RuntimeException ex) {
                        future.completeExceptionally(ex);
                    }
                    return future;
                }
            }
        }
        if (!commands.offer(new KvOpenCommand.Reader(store, settings, future))) {
            future.completeExceptionally(new IllegalStateException("kv open queue full"));
        }
        return future;
    }

    /**
     * Opens a writer store programmatically. Before {@link #start()} the store is declared, opened by
     * start, and the future completes once start has finished. Afterwards the open is queued and runs on the control thread.
     *
     * @param store    the store name
     * @param config   the writer store config (before YAML overrides), or {@code null} for native defaults
     * @param settings the per-store settings
     * @return a future completed with the open writer handle
     */
    public CompletableFuture<KvWriterStore> openWriter(
            String store, KvStoreConfig config, KvStoreRuntimeConfig settings) {
        Objects.requireNonNull(settings, "settings");
        CompletableFuture<KvWriterStore> future = new CompletableFuture<>();
        if (!KvRuntimeConfig.isValidStoreName(store)) {
            future.completeExceptionally(new IllegalArgumentException("invalid kv store name '" + store + "'"));
            return future;
        }
        if (closed) {
            future.completeExceptionally(new IllegalStateException("kv runtime closed"));
            return future;
        }
        if (!started) {
            synchronized (startLock) {
                if (!started) {
                    try {
                        declareWriter(store, config, settings);
                    } catch (RuntimeException ex) {
                        future.completeExceptionally(ex);
                        return future;
                    }
                    // Completed by start() once every store is open and the runtime is started.
                    pendingWriterFutures.add(Map.entry(store, future));
                    return future;
                }
            }
        }
        if (!commands.offer(new KvOpenCommand.Writer(store, config, settings, future))) {
            future.completeExceptionally(new IllegalStateException("kv open queue full"));
        }
        return future;
    }

    // ---- opens -----------------------------------------------------------------------------------

    private void openWriterNow(KvWriterStore writer) {
        KvStoreRuntimeConfig settings = writer.settings();
        long maxFrame = settings.maxFrameLength() == 0 ? defaultMaxFrameLength : settings.maxFrameLength();
        KvWriterOptions options = new KvWriterOptions(
                settings.acquire().nativeValue(), settings.openTimeoutMillis(), maxFrame, writer::recordState);
        String name = writer.storeName();
        KvWriter opened;
        try {
            opened = openWriterWithLeaderWait(name, writer.requestedConfig(), options, settings.openTimeoutMillis());
        } catch (RingloomException ex) {
            if (ex.statusCode() == RingloomStatus.KV_HELD_BY_OTHER) {
                throw new RingloomKvException(
                        "kv writer lease for store '" + name + "' is held by another client (acquire="
                                + settings.acquire() + ")",
                        ex.statusCode(),
                        ex);
            }
            throw new RingloomKvException(
                    "failed to open kv writer for store '" + name + "': " + ex.getMessage(), ex.statusCode(), ex);
        } catch (RuntimeException ex) {
            throw new RingloomKvException("failed to open kv writer for store '" + name + "'", -1, ex);
        }
        try {
            writer.attach(KvWriterPort.of(opened));
        } catch (RuntimeException ex) {
            try {
                opened.close();
            } catch (RuntimeException suppressed) {
                ex.addSuppressed(suppressed);
            }
            throw new RingloomKvException("failed to read kv writer state for store '" + name + "'", -1, ex);
        }
        noteValueLength(writer.config());
        if (logger != null) {
            logger.info(
                    "kv writer '{}' opened state={} epoch={} effective config {}",
                    name,
                    writer.state(),
                    writer.writerEpoch(),
                    writer.config());
        }
    }

    /**
     * Opens a writer, retrying while the backing topic leader is not yet known
     * ({@code TOPIC_LEADER_UNAVAILABLE}, typical right after service start) until the open timeout.
     * Runs on the starting thread or the control thread, so it may drive native control polling.
     */
    private KvWriter openWriterWithLeaderWait(
            String name, KvStoreConfig storeConfig, KvWriterOptions options, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            try {
                return client.openKvWriter(name, storeConfig, options);
            } catch (RingloomException ex) {
                if (ex.statusCode() != RingloomStatus.TOPIC_LEADER_UNAVAILABLE || System.nanoTime() - deadline >= 0) {
                    throw ex;
                }
            }
            service.pollControl(LEADER_WAIT_POLL_LIMIT);
            LockSupport.parkNanos(LEADER_WAIT_PARK_NANOS);
        }
    }

    /**
     * Attempts to open a reader. On startup, required readers and non-retryable failures throw; in the
     * background, non-retryable failures mark the store {@link KvStoreState#FAILED}.
     */
    private void openReaderNow(KvReaderStore reader, boolean startup) {
        KvStoreRuntimeConfig settings = reader.settings();
        String name = reader.name();
        long waitReady = startup && settings.required() ? settings.waitReadyTimeoutMillis() : 0;
        long maxReaders = Math.max(defaultMaxReaders, (long) settings.maxThreadSessions() + settings.sessionPoolSize());
        KvReaderOptions options = new KvReaderOptions(
                waitReady, settings.prefault(), settings.ttlClock().nativeValue(), maxReaders);
        KvReader opened;
        try {
            opened = client.openKvReader(name, options);
        } catch (RingloomException ex) {
            int code = ex.statusCode();
            if (startup && settings.required()) {
                throw new RingloomKvException(
                        "required kv store '" + name + "' not ready within " + waitReady + " ms: " + ex.getMessage(),
                        code,
                        ex);
            }
            if (!isRetryable(code)) {
                readerFailed(reader, startup, code, ex);
                return;
            }
            reader.markPending(System.nanoTime() + openRetryNanos);
            if (logger != null) {
                if (reader.failedAttempts() == 1) {
                    logger.info(
                            "kv store '{}' pending ({}); retrying every {} ms",
                            name,
                            ex.statusName(),
                            config.openRetryMillis());
                } else if (logger.isDebugEnabled()) {
                    logger.debug("kv store '{}' still pending ({})", name, ex.statusName());
                }
            }
            return;
        } catch (RuntimeException ex) {
            readerFailed(reader, startup, -1, ex);
            return;
        }
        try {
            reader.publish(opened);
        } catch (RuntimeException ex) {
            try {
                opened.close();
            } catch (RuntimeException suppressed) {
                ex.addSuppressed(suppressed);
            }
            readerFailed(reader, startup, -1, ex);
            return;
        }
        noteValueLength(reader.config());
        if (logger != null) {
            logger.info("kv reader '{}' open, config {}", name, reader.config());
        }
    }

    private void readerFailed(KvReaderStore reader, boolean startup, int code, RuntimeException ex) {
        String message = "kv reader for store '" + reader.name() + "' failed to open: " + ex.getMessage();
        if (startup) {
            throw new RingloomKvException(message, code, ex);
        }
        if (logger != null) {
            logger.error(message, ex);
        }
        reader.fail(new RingloomKvException(message, code, ex));
    }

    /** Deployment errors that retrying will not fix. */
    static boolean isRetryable(int status) {
        return switch (status) {
            case RingloomStatus.KV_NOT_AVAILABLE_LOCALLY,
                    RingloomStatus.KV_ENGINE_MISMATCH,
                    RingloomStatus.INVALID_ARGUMENT,
                    RingloomStatus.TOPIC_DISABLED -> false;
            default -> true;
        };
    }

    private int retryOnePendingReader(long now) {
        KvReaderStore[] rs = readerArray;
        int n = rs.length;
        for (int i = 0; i < n; i++) {
            int index = (retryCursor + i) % n;
            KvReaderStore reader = rs[index];
            if (reader.state() == KvStoreState.PENDING && now - reader.nextRetryNanos() >= 0) {
                retryCursor = index + 1;
                openReaderNow(reader, false);
                return 1;
            }
        }
        return 0;
    }

    private void execute(KvOpenCommand command) {
        switch (command) {
            case KvOpenCommand.Reader open -> {
                KvReaderStore reader =
                        readers.computeIfAbsent(open.store(), name -> newReaderStore(name, open.settings()));
                rebuildReaderArray();
                reader.addWaiter(open.future());
                if (reader.state() == KvStoreState.PENDING) {
                    openReaderNow(reader, false);
                }
            }
            case KvOpenCommand.Writer open -> {
                KvWriterStore existing = writers.get(open.store());
                if (existing != null) {
                    open.future().complete(existing);
                    return;
                }
                try {
                    KvStoreConfig base = open.config() == null ? KvStoreConfig.defaults() : open.config();
                    KvWriterStore writer = newWriterStore(
                            open.store(), open.settings().config().applyTo(base), open.settings());
                    openWriterNow(writer);
                    writers.put(open.store(), writer);
                    rebuildWriterArray();
                    open.future().complete(writer);
                } catch (RuntimeException ex) {
                    if (logger != null) {
                        logger.error("kv writer open for store '{}' failed", open.store(), ex);
                    }
                    open.future().completeExceptionally(ex);
                }
            }
        }
    }

    private void dispatchWriterState(KvWriterStateEvent event) {
        Consumer<KvWriterStateEvent> sink = stateSink;
        if (sink != null) {
            try {
                sink.accept(event);
            } catch (Throwable ex) {
                if (logger != null) {
                    logger.error("kv writer state handler for store '{}' failed", event.store(), ex);
                }
            }
        }
        for (Consumer<KvWriterStateEvent> listener : stateListeners) {
            try {
                listener.accept(event);
            } catch (Throwable ex) {
                if (logger != null) {
                    logger.error("kv writer state listener for store '{}' failed", event.store(), ex);
                }
            }
        }
    }

    private void rebuildReaderArray() {
        readerArray = readers.values().toArray(NO_READERS);
    }

    private void rebuildWriterArray() {
        writerArray = writers.values().toArray(NO_WRITERS);
    }

    private void warn(String message, Throwable ex) {
        if (logger != null) {
            logger.warn(message, ex);
        }
    }
}
