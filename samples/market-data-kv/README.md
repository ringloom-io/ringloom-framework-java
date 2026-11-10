# Market Data Replicated KV Sample

Replicated KV store sample using generated `@RingloomKvStore` proxies, SBE values, Avaje injection, and a standby writer.

Topology:

- `price-writer` holds the writer lease of the `prices` store and republishes a `PriceSnapshot` (SBE) for every instrument twice a second from a `@RingloomSchedule` method, in one batch per tick. Its YAML sets `acquire: wait`, so a second instance queues as a standby instead of failing startup. A `@RingloomKvWriterStateHandler` prints every lease transition.
- `price-reader` serves `PriceRequest` messages with a `@RingloomHandler` on `consumerThread` dispatch. Each lookup is `PriceStore.get(instrumentId)`, which reads the local LMDB replica through shared memory (no broker round trip) and returns a reusable `PriceSnapshotDecoder` flyweight without allocating. The store is `required: false`, so the reader starts before any writer and answers "no price yet" until the first snapshot arrives.
- `price-terminal` asks the reader for every instrument once a second and prints the price and the `writerEpoch` that produced it. A standby takeover shows up as a new epoch.

Keys are `long` instrument ids, encoded inline by the generated proxy as 8 big-endian bytes with the sign bit flipped (the cross-language key contract in `docs/architecture.md`).

## Broker prerequisite

The broker config enables persistent topics and KV stores (`broker.topics.enabled=true`, `broker.kv.enabled=true`); KV writes travel over the backing topic `__kv.prices`.

```/dev/null/commands.sh#L1-3
cd /home/dragan/code/ringloom
zig build
zig build run -- --config /home/dragan/code/ringloom-framework-java/samples/market-data-kv/config/broker.properties
```

## Build

```/dev/null/commands.sh#L1-2
cd /home/dragan/code/ringloom-framework-java
./gradlew :samples:market-data-kv:build
```

## Run

```/dev/null/commands.sh#L1-2
cd /home/dragan/code/ringloom-framework-java
sh samples/market-data-kv/scripts/run.sh
```

The script starts the reader, two writers, and the terminal (`ROUNDS=60 sh .../run.sh` changes how many rounds the terminal prints). The writers print:

```/dev/null/output.txt#L1-2
writer lease for 'prices': null -> GRANTED (epoch 1)     # first writer
writer lease for 'prices': null -> QUEUED (epoch 0)      # standby
```

To run the processes by hand, use separate terminals:

```/dev/null/commands.sh#L1-4
./gradlew :samples:market-data-kv:runPriceReader
./gradlew :samples:market-data-kv:runPriceWriter      # active writer
./gradlew :samples:market-data-kv:runPriceWriter      # standby writer
./gradlew :samples:market-data-kv:runPriceTerminal --args=120
```

## Standby takeover

Stop the active writer (Ctrl+C in its terminal, or `kill <pid>` of its `PriceWriterApp` JVM). On close the framework completes pending acks and closes the native writer, which appends a `WRITER_RELEASE` record. The broker then grants the lease to the standby, which prints:

```/dev/null/output.txt#L1-1
writer lease for 'prices': QUEUED -> GRANTED (epoch 2)
```

and starts publishing. The terminal output switches from `writerEpoch=1` to `writerEpoch=2` (update ids restart because each writer keeps its own counter). The reader needs no action: it keeps reading the same local replica.

## Notes

- All services must use the same RingLoom group as `config/broker.properties` (`market-data-java-kv`).
- `price-writer.yaml` sets `acquire: wait`. With the default `failIfHeld`, the second writer would fail startup with `KV_HELD_BY_OTHER`.
- The flyweight returned by `PriceStore.get` points into the calling thread's KV scratch memory. It is valid until the next KV read on the same thread, so the handler copies the fields into the response before returning.
- The `test` source set of this module holds the generated-store integration and allocation tests (`./gradlew :samples:market-data-kv:test -Pringloom.brokerBin=... -Pringloom.repoRoot=...`; skipped without a broker).
