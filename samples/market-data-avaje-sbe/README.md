# Market Data Avaje/SBE Sample

Request/response market-data sample using Avaje injection, generated SBE codecs, and partitioned worker dispatch.

Topology:

- `market-terminal` reuses a mutable `QuoteRequestDto` and calls a generated blocking request client.
- `market-pricing` handles fixed-size SBE `QuoteRequestDecoder` flyweights on partitioned workers keyed by `instrumentId`.
- The pricing handler is an Avaje `@Component`; Avaje injects the generated direct reply client, and the handler reuses per-worker `QuoteResponseDto` instances.

## Broker prerequisite

```/dev/null/commands.sh#L1-3
cd /home/dragan/code/ringloom
zig build
zig build run -- --config /home/dragan/code/ringloom-framework-java/samples/market-data-avaje-sbe/config/broker.properties
```

## Build

```/dev/null/commands.sh#L1-2
cd /home/dragan/code/ringloom-framework-java
./gradlew :samples:market-data-avaje-sbe:build
```

## Run

```/dev/null/commands.sh#L1-2
cd /home/dragan/code/ringloom-framework-java
sh samples/market-data-avaje-sbe/scripts/run.sh
```

The pricing service YAML selects `partitionedWorkers` and sets `maxPayloadBytes` to the fixed SBE request block length so worker handoff uses preallocated native slots without per-message heap payload allocation.

Both service YAML files must use the same RingLoom group as `config/broker.properties`; the sample broker config currently uses `market-data-java-avaje`.
