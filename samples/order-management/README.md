# Order Management Sample

This sample demonstrates an allocation-conscious Java RingLoom application built with the framework annotation processor, generated SBE DTOs for outbound sends, and generated SBE flyweight decoders for inbound handlers.

Topology:

- `order-simulator` generates deterministic orders.
- `order-gateway` validates orders and forwards risk checks.
- `risk-service` enforces account and symbol limits.
- `matching-engine` emits fills.
- `execution-service` converts fills to execution reports.
- `portfolio-service` applies position updates.

## Broker prerequisite

Start a RingLoom broker first. From the `ringloom` repository, build the broker and run it with the sample config from this module:

```/dev/null/commands.sh#L1-3
cd /home/dragan/code/ringloom
zig build
zig build run -- --config /home/dragan/code/ringloom-framework-java/samples/order-management/config/broker.properties
```

The broker config uses group `order-management-java` and stores metadata under `/tmp/ringloom-framework-order-management/storage`.

## Build

```/dev/null/commands.sh#L1-2
cd /home/dragan/code/ringloom-framework-java
./gradlew :samples:order-management:build
```

## Run all sample services

With the broker running in another terminal:

```/dev/null/commands.sh#L1-3
cd /home/dragan/code/ringloom-framework-java
sh samples/order-management/scripts/run.sh
ORDERS=100000 RATE_PER_SEC=50000 sh samples/order-management/scripts/run.sh
```

## Run services manually

Start long-running services first, then run the simulator. Each service boots from its own built-in `DEFAULT_CONFIG` YAML path via `RingloomBootstrap.fromYaml(...)`.

```/dev/null/commands.sh#L1-6
./gradlew :samples:order-management:runPortfolioService
./gradlew :samples:order-management:runExecutionService
./gradlew :samples:order-management:runMatchingEngine
./gradlew :samples:order-management:runRiskService
./gradlew :samples:order-management:runOrderGateway
./gradlew :samples:order-management:runOrderSimulator --args="10000 25000"
```

The long-running services call `RingloomApplicationRunner.awaitShutdown()`. The sample YAML files are intentionally minimal and rely on framework defaults for runtime execution, buffer sizing, broker node id, and shutdown hooks.

## What to look at

- Annotation-driven clients and handlers live under the per-service source sets in `src/*/java`.
- Service mains are intentionally small: the top-level class imports and uses `@RingloomApplication`, and boots via `RingloomBootstrap.fromYaml(DEFAULT_CONFIG)`.
- The per-service YAML files are deliberately tiny: they override only `service.name`, the shared sample storage path/group, and `serializers.default`.
- Generated clients still perform `tryClaim`, encode directly into RingLoom claim memory, and then commit the claim.
- `build.gradle.kts` runs `uk.co.real_logic.sbe.SbeTool` against `src/main/resources/messages.xml`, enables DTO generation, and compiles the generated codecs into the sample module.
- Outbound client APIs use generated SBE DTOs such as `NewOrderDto`; inbound handlers use generated SBE decoders such as `NewOrderDecoder`.
- Template ids now come directly from generated SBE codec constants such as `NewOrderEncoder.TEMPLATE_ID` and `ExecutionReportDecoder.TEMPLATE_ID` rather than a hand-maintained sample constant list.
- Because the YAML configs set `ringloom.serializers.default: sbe`, the sample annotations no longer need an explicit `serializer = "sbe"` on every `@RingloomRequest` and `@RingloomHandler`.
- The RingLoom annotation processor now generates serializer registrations for SBE DTO client payloads and SBE flyweight handler payloads, so the sample no longer hand-wires a `SerializerRegistry`.
- The runtime uses consumer-thread dispatch so handlers observe borrowed payload memory without crossing a thread boundary or copying.
