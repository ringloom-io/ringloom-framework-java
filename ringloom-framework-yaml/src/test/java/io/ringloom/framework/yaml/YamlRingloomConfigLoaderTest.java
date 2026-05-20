// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.framework.config.MessageExecutionMode;
import io.ringloom.framework.config.RuntimeMode;
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
                scheduler:
                  maxTimers: 32
                  tickResolutionNanos: 1024
                  ticksPerWheel: 64
                  initialTickAllocation: 4
                  pollLimit: 8
                messages:
                  execution:
                    mode: virtualThreads
                    virtualThreads:
                      maxInFlight: 17
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
        assertThat(config.runtime().scheduler().maxTimers()).isEqualTo(32);
        assertThat(config.runtime().scheduler().tickResolutionNanos()).isEqualTo(1024);
        assertThat(config.runtime().scheduler().ticksPerWheel()).isEqualTo(64);
        assertThat(config.runtime().scheduler().initialTickAllocation()).isEqualTo(4);
        assertThat(config.runtime().scheduler().pollLimit()).isEqualTo(8);
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
}
