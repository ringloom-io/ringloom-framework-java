# RingLoom Framework for Java

RingLoom Framework for Java is a compile-time-wired Java framework for building
RingLoom services on top of the low-level Java FFM service bindings. It provides
runtime bootstrap, generated clients and dispatchers, message execution policies,
request/response support, serializer integration, YAML configuration, metrics
facades, and Avaje IoC integration while keeping hot paths free from runtime
annotation scanning.

The project currently targets **Java 25** and is published from this repository
as a Gradle multi-project build.

## Features

- Source-retention annotations for service applications, client interfaces, and
  message handlers.
- Annotation processor that generates client proxies, dispatchers, and
  `ServiceLoader` bootstrap metadata.
- Runtime lifecycle management around `RingloomRuntime` and the native
  `ringloom-java-bindings` service API.
- Execution modes for consumer-thread dispatch, partitioned workers, and virtual
  threads.
- Request/response registry with callback-oriented and virtual-thread-friendly
  correlation support.
- Pluggable serializer SPI with optional SBE and Apache Fory modules.
- Strict YAML configuration loader.
- Metrics and tracing extension points.
- Avaje Inject module that exposes RingLoom runtime components as injectable
  beans.

## Modules

| Module | Purpose |
| --- | --- |
| `ringloom-framework-core` | Runtime, bootstrap, annotations, generated-code contracts, execution policies, request registry, serializer SPI, metrics, and tracing APIs. |
| `ringloom-framework-processor` | Annotation processor that generates client proxies, dispatchers, and generated application providers. |
| `ringloom-framework-yaml` | YAML-to-config loader discovered by `RingloomBootstrap.fromYaml(...)` through `ServiceLoader`. |
| `ringloom-serializer-sbe` | SBE/Agrona-oriented serializer primitives and `MemorySegment` buffer adapters for low-allocation message paths. |
| `ringloom-serializer-fory` | Apache Fory serializer module for ergonomic POJO serialization. |
| `ringloom-ioc-avaje` | Avaje Inject integration for registering RingLoom runtime, generated metadata, serializers, metrics, request registry, dispatcher, and generated clients as beans. |
| `ringloom-metrics-opentelemetry` | Optional OpenTelemetry metrics bridge that exposes native-backed RingLoom counters and gauges as async instruments. |
| `ringloom-tracing-opentelemetry` | Optional OpenTelemetry `TraceAdapter` for sampled local send/receive spans. |

## Requirements

- JDK 25.
- Gradle wrapper from this repository.
- Native RingLoom broker/service runtime available when starting real services.

## Getting started

Clone the repository and run the build:

```bash
./gradlew build
```

Run all checks:

```bash
./gradlew check
```

Run a focused module test:

```bash
./gradlew :ringloom-framework-core:test
```

Format Java sources:

```bash
./gradlew spotlessApply
```

## Basic usage

Define a RingLoom application and generated client interfaces in application
source. Annotations are retained only in source and consumed by the annotation
processor.

```java
import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.annotation.RingloomServiceComponent;
import io.ringloom.framework.dispatch.MessageContext;
import java.lang.foreign.MemorySegment;

@RingloomApplication(service = "orders")
public final class OrdersApplication {}

@RingloomClient(service = "pricing")
public interface PricingClient {
    @RingloomRequest(templateId = 1001)
    int requestPrice(MemorySegment payload);
}

@RingloomServiceComponent
public final class OrderHandlers {
    @RingloomHandler(templateId = 2001)
    public int onOrder(MemorySegment payload, MessageContext context) {
        return 0;
    }
}
```

Start a standalone service from YAML when `ringloom-framework-yaml` is on the
classpath:

```java
import io.ringloom.framework.RingloomApplicationRunner;
import io.ringloom.framework.RingloomBootstrap;

public final class OrdersMain {
    public static void main(String[] args) throws Exception {
        try (RingloomApplicationRunner app = RingloomBootstrap.fromYaml(args[0]).start()) {
            app.awaitShutdown();
        }
    }
}
```

## Serializers

Serializer support is explicit and name-based through `SerializerRegistry`.
Generated POJO clients and handlers select serializers with annotation metadata:

```java
@RingloomRequest(templateId = 3001, serializer = "fory")
int publish(OrderCreated event);

@RingloomHandler(templateId = 3001, serializer = "fory")
int onOrderCreated(OrderCreated event, MessageContext context);
```

Use `ringloom-serializer-sbe` for SBE/Agrona codec integration and
`ringloom-serializer-fory` for Apache Fory-backed POJO serialization. Serializer
modules are optional and are not pulled into core by default.

## Avaje Inject integration

`ringloom-ioc-avaje` provides `RingloomAvajeModule`, an Avaje custom module that
registers RingLoom beans in a `BeanScope`.

```java
import io.avaje.inject.BeanScope;
import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.ioc.avaje.RingloomAvajeModule;

try (BeanScope scope = BeanScope.builder()
        .bean(RingloomApplicationConfig.class, RingloomApplicationConfig.minimal("orders"))
        .modules(new RingloomAvajeModule())
        .build()) {
    // Inject or retrieve RingLoom beans from the scope.
}
```

The module can also load configuration from the Avaje property
`ringloom.config.path` when no `RingloomApplicationConfig` bean is provided.

## Documentation

Design and implementation notes live under `docs/`:

- `docs/architecture.md` describes the framework architecture.
- `docs/impl/` contains phase documents for the framework skeleton, annotation
  processor, YAML bootstrap, serializers, metrics, execution modes, tracing,
  Avaje IoC, and request/response support.

## Development

This repository uses Spotless with Palantir Java Format. Java compilation tasks
run formatting before compilation, and dependency/plugin versions are managed in
`gradle/libs.versions.toml`.

Useful commands:

```bash
./gradlew build
./gradlew test
./gradlew spotlessCheck
./gradlew :ringloom-framework-processor:test
./gradlew :ringloom-ioc-avaje:test
```

## License

This project is licensed under the Apache License 2.0. See `LICENSE` for
details.
