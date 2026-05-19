# Order Management Avaje/Fory Sample

Same topology as `samples/order-management`, but booted through `AvajeRingloomBootstrap` and serialized with generated Apache Fory record registration instead of SBE codecs.

Topology:

- `order-simulator` generates deterministic orders.
- `order-gateway` validates orders and forwards risk checks.
- `risk-service` enforces account and symbol limits.
- `matching-engine` emits fills.
- `execution-service` converts fills to execution reports.
- `portfolio-service` applies position updates.

## Broker prerequisite

```/dev/null/commands.sh#L1-3
cd /home/dragan/code/ringloom
zig build
zig build run -- --config /home/dragan/code/ringloom-framework-java/samples/order-management-avaje/config/broker.properties
```

The broker config uses group `order-management-java-avaje` and storage `/tmp/ringloom-framework-order-management-avaje/storage`.

## Build

```/dev/null/commands.sh#L1-2
cd /home/dragan/code/ringloom-framework-java
./gradlew :samples:order-management-avaje:build
```

## Run

```/dev/null/commands.sh#L1-3
cd /home/dragan/code/ringloom-framework-java
sh samples/order-management-avaje/scripts/run.sh
ORDERS=100000 RATE_PER_SEC=50000 sh samples/order-management-avaje/scripts/run.sh
```

Manual tasks:

```/dev/null/commands.sh#L1-6
./gradlew :samples:order-management-avaje:runPortfolioService
./gradlew :samples:order-management-avaje:runExecutionService
./gradlew :samples:order-management-avaje:runMatchingEngine
./gradlew :samples:order-management-avaje:runRiskService
./gradlew :samples:order-management-avaje:runOrderGateway
./gradlew :samples:order-management-avaje:runOrderSimulator --args="10000 25000"
```

Look at the `src/*/java` service mains for `AvajeRingloomBootstrap.fromYaml(...)`, handlers for `@Component`/`@Inject` client injection, and `src/main/java/io/ringloom/samples/orders/model` for the Fory payload records that are registered by generated application metadata.
