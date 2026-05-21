## Benchmark results

TODO

Run all commands from the repository root with **Java 25**.

```bash
./gradlew :ringloom-framework-core:jmh
```

Run only one benchmark:

```bash
./gradlew :ringloom-framework-core:jmh \
  -PjmhArgs='PartitionedWorkerExecutionPolicyBenchmark'
```

Run a quick smoke benchmark:

```bash
./gradlew :ringloom-framework-core:jmh \
  -PjmhArgs='GeneratedTracingHookBenchmark -wi 0 -i 1 -r 100ms -f 1'
```

The Gradle task passes the framework's required Java 25 runtime flags to the JMH
forks:

```text
--enable-native-access=ALL-UNNAMED
--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
-Xshare:off
```

## Benchmark parameters

The JMH benchmarks use `@Param` defaults that match the previous benchmark
settings. Override them with standard JMH `-p` arguments:

| JMH parameter | Partitioned workers | Virtual threads |
| --- | --- | --- |
| `payloadBytes` | `128` | `128` |
| `workers` | `4` | n/a |
| `queueCapacity` | `1024` | n/a |
| `maxInFlight` | n/a | `10000` |

Examples:

```bash
./gradlew :ringloom-framework-core:jmh \
  -PjmhArgs='PartitionedWorkerExecutionPolicyBenchmark -p workers=2,4,8 -p queueCapacity=1024'
```

```bash
./gradlew :ringloom-framework-core:jmh \
  -PjmhArgs='VirtualThreadExecutionPolicyBenchmark -p maxInFlight=1000,10000 -p payloadBytes=128,256'
```

Trace-hook overhead can be measured separately:

```bash
./gradlew :ringloom-framework-core:jmh \
  -PjmhArgs='GeneratedTracingHookBenchmark'
```

Native metric slot update overhead can be compared with a heap `LongAdder`
baseline:

```bash
./gradlew :ringloom-framework-core:jmh \
  -PjmhArgs='MetricUpdateBenchmark'
```

Each benchmark invocation dispatches a fixed batch of 16,384 messages and waits
for the asynchronous policy to process the full batch. JMH reports throughput as
messages per second via `@OperationsPerInvocation`.
