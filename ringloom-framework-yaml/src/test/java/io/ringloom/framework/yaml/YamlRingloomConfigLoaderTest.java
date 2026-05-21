// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.framework.config.MessageExecutionMode;
import io.ringloom.framework.config.RuntimeMode;
import io.ringloom.framework.config.TracingPropagationMode;
import io.ringloom.framework.config.TracingSamplerKind;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class YamlRingloomConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void mapsYamlToCoreConfig() throws Exception {
        // Given
        Path file = tempDir.resolve("ringloom.yaml");
        Files.writeString(file, """
            ringloom:
              service:
                name: orders
              runtime:
                mode: shared
                control:
                  cpuCore: 2
                messages:
                  cpuCore: 2
                  idleStrategy: yielding
                  execution:
                    mode: virtualThreads
                    virtualThreads:
                      maxInFlight: 17
                scheduler:
                  maxTimers: 32
                  tickResolutionNanos: 1024
                  ticksPerWheel: 64
                  initialTickAllocation: 4
                  pollLimit: 8
                tracing:
                  enabled: true
                  sampler: traceIdRatio
                  sampleRatio: 0.25
                  propagation: payloadPrefix
                  includeDecodeTime: false
              serializers:
                default: sbe
                entries:
                  sbe:
                    type: sbe
              clients:
                pricing:
                  service: pricing
                  routing: leader
                  serializer: sbe
            """);

        // When
        var config = new YamlRingloomConfigLoader().load(file);

        // Then
        assertThat(config.service().name()).isEqualTo("orders");
        assertThat(config.runtime().mode()).isEqualTo(RuntimeMode.SHARED);
        assertThat(config.runtime().control().cpuCore()).isEqualTo(2);
        assertThat(config.runtime().messages().cpuCore()).isEqualTo(2);
        assertThat(config.runtime().scheduler().maxTimers()).isEqualTo(32);
        assertThat(config.runtime().scheduler().tickResolutionNanos()).isEqualTo(1024);
        assertThat(config.runtime().scheduler().ticksPerWheel()).isEqualTo(64);
        assertThat(config.runtime().scheduler().initialTickAllocation()).isEqualTo(4);
        assertThat(config.runtime().scheduler().pollLimit()).isEqualTo(8);
        assertThat(config.runtime().tracing().enabled()).isTrue();
        assertThat(config.runtime().tracing().sampler()).isEqualTo(TracingSamplerKind.TRACE_ID_RATIO);
        assertThat(config.runtime().tracing().sampleRatio()).isEqualTo(0.25);
        assertThat(config.runtime().tracing().propagation()).isEqualTo(TracingPropagationMode.PAYLOAD_PREFIX);
        assertThat(config.runtime().tracing().includeDecodeTime()).isFalse();
        assertThat(config.runtime().execution().mode()).isEqualTo(MessageExecutionMode.VIRTUAL_THREADS);
        assertThat(config.runtime().execution().virtualThreads().maxInFlight()).isEqualTo(17);
        assertThat(config.clients().get("pricing").service()).isEqualTo("pricing");
    }

    @Test
    void rejectsUnknownKeys() throws Exception {
        // Given
        Path file = tempDir.resolve("bad.yaml");
        Files.writeString(file, """
            ringloom:
              service:
                name: orders
                surprise: true
            """);

        // When / Then
        assertThatThrownBy(() -> new YamlRingloomConfigLoader().load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown key ringloom.service.surprise");
    }

    @Test
    void rejectsUnknownTracingKeys() throws Exception {
        // Given
        Path file = tempDir.resolve("bad-tracing-key.yaml");
        Files.writeString(file, """
            ringloom:
              service:
                name: orders
              runtime:
                tracing:
                  enabled: true
                  surprise: true
            """);

        // When / Then
        assertThatThrownBy(() -> new YamlRingloomConfigLoader().load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown key ringloom.runtime.tracing.surprise");
    }

    @Test
    void rejectsInvalidTracingValues() throws Exception {
        // Given
        Path file = tempDir.resolve("bad-tracing-value.yaml");
        Files.writeString(file, """
            ringloom:
              service:
                name: orders
              runtime:
                tracing:
                  enabled: true
                  sampler: unknown
            """);

        // When / Then
        assertThatThrownBy(() -> new YamlRingloomConfigLoader().load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown tracing sampler unknown");
    }

    @Test
    void rejectsInvalidTracingSampleRatio() throws Exception {
        // Given
        Path file = tempDir.resolve("bad-tracing-ratio.yaml");
        Files.writeString(file, """
            ringloom:
              service:
                name: orders
              runtime:
                tracing:
                  enabled: true
                  sampler: traceIdRatio
                  sampleRatio: 2.0
            """);

        // When / Then
        assertThatThrownBy(() -> new YamlRingloomConfigLoader().load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tracing.sampleRatio must be between 0.0 and 1.0");
    }
}
