# Pricing Requests Avaje/Fory Sample

Small request/response sample focused on virtual-thread dispatch, generated Apache Fory payload registration, and Avaje component injection.

Topology:

- `pricing-terminal` starts a few virtual threads and calls a blocking generated `PricingClient`.
- `pricing-service` handles quote requests on virtual-thread dispatchers and injects a direct reply client.
- Responses use the original `MessageContext` source node/service and correlation id, so the terminal receives a normal synchronous `PriceQuote`.

## Broker prerequisite

```/dev/null/commands.sh#L1-3
cd /home/dragan/code/ringloom
zig build
zig build run -- --config /home/dragan/code/ringloom-framework-java/samples/pricing-requests-avaje/config/broker.properties
```

## Build

```/dev/null/commands.sh#L1-2
cd /home/dragan/code/ringloom-framework-java
./gradlew :samples:pricing-requests-avaje:build
```

## Run

```/dev/null/commands.sh#L1-2
cd /home/dragan/code/ringloom-framework-java
sh samples/pricing-requests-avaje/scripts/run.sh
```
