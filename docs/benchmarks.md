## Benchmark results

These measurements were captured from the gated benchmark tests in
`ringloom-framework-core` on **2026-05-20** using the default harness settings.
They are in-process microbenchmarks, so they do **not** require a running
RingLoom broker.

| Policy | Benchmark test | Messages | Payload | Concurrency setting | Elapsed | Throughput |
| --- | --- | ---: | ---: | --- | ---: | ---: |
| Partitioned workers (Agrona SPSC ring buffer) | `PartitionedWorkerExecutionPolicyBenchmarkTest` | 500,000 | 128 B | `workers=4`, `queueCapacity=1024` | 69.145 ms | 7,231,209 msg/s |
| Virtual threads | `VirtualThreadExecutionPolicyBenchmarkTest` | 500,000 | 128 B | `maxInFlight=10000` | 202.916 ms | 2,464,075 msg/s |

### Historical partitioned-worker baseline

Before the Agrona SPSC ring-buffer change, the previous partitioned-worker
implementation (JCTools MPSC slot pool) measured:

| Policy | Elapsed | Throughput |
| --- | ---: | ---: |
| Partitioned workers (previous MPSC slot pool) | 76.795 ms | 6,510,808 msg/s |

## Reproducing the benchmarks

Run all commands from the repository root with **Java 25**.

### Partitioned workers

```bash
RINGLOOM_BENCHMARK=true \
./gradlew :ringloom-framework-core:test \
  --tests 'io.ringloom.framework.dispatch.PartitionedWorkerExecutionPolicyBenchmarkTest.partitionedWorkerThroughputBenchmark' \
  --rerun-tasks \
  --info
```

### Virtual threads

```bash
RINGLOOM_BENCHMARK=true \
./gradlew :ringloom-framework-core:test \
  --tests 'io.ringloom.framework.dispatch.VirtualThreadExecutionPolicyBenchmarkTest.virtualThreadThroughputBenchmark' \
  --rerun-tasks \
  --info
```

## Benchmark parameters

Both benchmark tests accept the following JVM system properties and use the
values below by default:

| Property | Partitioned workers | Virtual threads |
| --- | --- | --- |
| `ringloom.benchmark.messages` | `500000` | `500000` |
| `ringloom.benchmark.payloadBytes` | `128` | `128` |
| `ringloom.benchmark.workers` | `4` | n/a |
| `ringloom.benchmark.queueCapacity` | `1024` | n/a |
| `ringloom.benchmark.maxInFlight` | n/a | `10000` |

Example with overrides:

```bash
RINGLOOM_BENCHMARK=true \
./gradlew :ringloom-framework-core:test \
  --tests 'io.ringloom.framework.dispatch.VirtualThreadExecutionPolicyBenchmarkTest.virtualThreadThroughputBenchmark' \
  --rerun-tasks \
  --info \
  -Dringloom.benchmark.messages=1000000 \
  -Dringloom.benchmark.payloadBytes=256 \
  -Dringloom.benchmark.maxInFlight=20000
```
