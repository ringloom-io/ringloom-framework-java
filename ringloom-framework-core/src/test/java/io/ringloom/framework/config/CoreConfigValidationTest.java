// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.framework.annotation.RoutingMode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CoreConfigValidationTest {
    @Test
    void normalizesRuntimeDefaults() {
        // Given / When
        RingloomRuntimeConfig config = new RingloomRuntimeConfig(null, null, null, null, null, null, false, null);

        // Then
        assertThat(config.mode()).isEqualTo(RuntimeMode.DEDICATED);
        assertThat(config.control()).isEqualTo(RingloomEventLoopConfig.defaults());
        assertThat(config.messages()).isEqualTo(RingloomEventLoopConfig.defaults());
        assertThat(config.execution().mode()).isEqualTo(MessageExecutionMode.CONSUMER_THREAD);
        assertThat(config.scheduler()).isEqualTo(SchedulerRuntimeConfig.defaults());
        assertThat(config.requests()).isEqualTo(RequestRuntimeConfig.defaults());
        assertThat(config.tracing()).isEqualTo(TracingRuntimeConfig.defaults());
        assertThat(config.tracing().includeDecodeTime()).isTrue();
    }

    @Test
    void normalizesExecutionDefaults() {
        // Given / When
        MessageExecutionConfig config = new MessageExecutionConfig(null, null, null);

        // Then
        assertThat(config.mode()).isEqualTo(MessageExecutionMode.CONSUMER_THREAD);
        assertThat(config.partitioned()).isEqualTo(PartitionedExecutionConfig.defaults());
        assertThat(config.virtualThreads()).isEqualTo(VirtualThreadExecutionConfig.defaults());
    }

    @Test
    void rejectsInvalidPartitionedExecutionSettings() {
        // Given / When / Then
        assertThatThrownBy(() -> new PartitionedExecutionConfig(0, 3, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("partitioned.queueCapacity must be a positive power of two");
        assertThatThrownBy(() -> new PartitionedExecutionConfig(-1, 1024, 4096, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("partitioned.workers must be positive");
        assertThatThrownBy(() -> new PartitionedExecutionConfig(1, 1024, -1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("partitioned.maxPayloadBytes must be positive");
    }

    @Test
    void rejectsInvalidRequestRuntimeSettings() {
        // Given / When / Then
        assertThatThrownBy(() -> new RequestRuntimeConfig(-1, Duration.ofSeconds(1), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requests.maxPending must be positive");
        assertThatThrownBy(() -> new RequestRuntimeConfig(1, Duration.ZERO, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requests.defaultTimeout must be positive");
    }

    @Test
    void rejectsInvalidSchedulerRuntimeSettings() {
        // Given / When / Then
        assertThatThrownBy(() -> new SchedulerRuntimeConfig(-1, 1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scheduler.maxTimers must be positive");
        assertThatThrownBy(() -> new SchedulerRuntimeConfig(1, 3, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scheduler.tickResolutionNanos must be a positive power of two");
        assertThatThrownBy(() -> new SchedulerRuntimeConfig(1, 1, 3, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scheduler.ticksPerWheel must be a positive power of two");
        assertThatThrownBy(() -> new SchedulerRuntimeConfig(1, 1, 1, 3, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scheduler.initialTickAllocation must be a positive power of two");
        assertThatThrownBy(() -> new SchedulerRuntimeConfig(1, 1, 1, 1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scheduler.pollLimit must be positive");
    }

    @Test
    void validatesTracingRuntimeSettings() {
        // Given / When / Then
        assertThat(new TracingRuntimeConfig(true, TracingSamplerKind.TRACE_ID_RATIO, 0.25, null, false).propagation())
                .isEqualTo(TracingPropagationMode.NONE);
        assertThatThrownBy(() -> new TracingRuntimeConfig(true, TracingSamplerKind.TRACE_ID_RATIO, -0.1, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tracing.sampleRatio must be between 0.0 and 1.0");
        assertThatThrownBy(() -> new TracingRuntimeConfig(true, TracingSamplerKind.TRACE_ID_RATIO, 1.1, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tracing.sampleRatio must be between 0.0 and 1.0");
        assertThatThrownBy(
                        () -> new TracingRuntimeConfig(true, TracingSamplerKind.TRACE_ID_RATIO, Double.NaN, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tracing.sampleRatio must be between 0.0 and 1.0");
    }

    @Test
    void validatesEventLoopAndVirtualThreadSettings() {
        // Given / When / Then
        assertThatThrownBy(() -> new RingloomEventLoopConfig(null, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pollLimit must be non-negative");
        assertThatThrownBy(() -> new RingloomEventLoopConfig(null, 1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cpuCore must be between 0 and 1023");
        assertThatThrownBy(() -> new RingloomEventLoopConfig(null, 1, 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cpuCore must be between 0 and 1023");
        assertThatThrownBy(() -> new VirtualThreadExecutionConfig(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("virtualThreads.maxInFlight must be positive");
    }

    @Test
    void validatesRuntimeAffinitySettings() {
        // Given
        RingloomEventLoopConfig controlPinned = new RingloomEventLoopConfig(null, 1, 2);
        RingloomEventLoopConfig messagesPinned = new RingloomEventLoopConfig(null, 1, 3);
        RingloomEventLoopConfig samePinned = new RingloomEventLoopConfig(null, 1, 2);

        // When / Then
        assertThat(new RingloomRuntimeConfig(
                                RuntimeMode.DEDICATED, controlPinned, messagesPinned, null, null, null, false, null)
                        .control()
                        .cpuCore())
                .isEqualTo(2);
        assertThatThrownBy(() -> new RingloomRuntimeConfig(
                        RuntimeMode.SHARED, controlPinned, messagesPinned, null, null, null, false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "shared runtime mode uses one event-loop thread, so control.cpuCore and messages.cpuCore must match");
        assertThat(new RingloomRuntimeConfig(
                                RuntimeMode.SHARED, controlPinned, samePinned, null, null, null, false, null)
                        .messages()
                        .cpuCore())
                .isEqualTo(2);
        assertThatThrownBy(() -> new RingloomRuntimeConfig(
                        RuntimeMode.EXTERNAL, controlPinned, null, null, null, null, false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event-loop cpuCore cannot be configured in external runtime mode");
    }

    @Test
    void normalizesAndValidatesClientRuntimeConfig() {
        // Given / When
        RingloomClientRuntimeConfig config = new RingloomClientRuntimeConfig("pricing", "orders", null, null);

        // Then
        assertThat(config.routing()).isEqualTo(RoutingMode.LOAD_BALANCED);
        assertThat(config.serializer()).isEmpty();
        assertThatThrownBy(() -> new RingloomClientRuntimeConfig("", "orders", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("client alias must not be blank");
        assertThatThrownBy(() -> new RingloomClientRuntimeConfig("pricing", "", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("client service must not be blank");
    }

    @Test
    void validatesSerializerConfigNamesAndCopiesEntries() {
        // Given
        Map<String, Object> entry = Map.of("type", "fory");
        Map<String, Map<String, Object>> entries = Map.of("fory", entry);

        // When
        RingloomSerializerConfig config = new RingloomSerializerConfig(null, entries);

        // Then
        assertThat(config.defaultSerializer()).isEmpty();
        assertThat(config.entry("fory")).containsEntry("type", "fory");
        assertThat(config.entries()).containsOnlyKeys("fory");
        assertThatThrownBy(() -> new RingloomSerializerConfig("fory", Map.of("", entry)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("serializer names must not be blank");
    }

    @Test
    void validatesServiceRuntimeSettings() {
        // Given / When / Then
        assertThatThrownBy(() -> RingloomServiceRuntimeConfig.of(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("service.name must not be blank");
        assertThatThrownBy(
                        () -> new RingloomServiceRuntimeConfig("orders", null, null, (short) 0, false, -1, 0, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("service.heartbeatTimeoutMillis must be non-negative");
        assertThatThrownBy(
                        () -> new RingloomServiceRuntimeConfig("orders", null, null, (short) 0, false, 0, 0, 3, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("service.messagesBufferLength must be a positive power of two");
    }
}
