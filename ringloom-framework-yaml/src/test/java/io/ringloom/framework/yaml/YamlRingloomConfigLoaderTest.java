// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.yaml;

import io.ringloom.framework.config.MessageExecutionMode;
import io.ringloom.framework.config.RuntimeMode;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class YamlRingloomConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void mapsYamlToCoreConfig() throws Exception {
        Path file = tempDir.resolve("ringloom.yaml");
        Files.writeString(file, """
            ringloom:
              service:
                name: orders
              runtime:
                mode: shared
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

        var config = new YamlRingloomConfigLoader().load(file);

        assertEquals("orders", config.service().name());
        assertEquals(RuntimeMode.SHARED, config.runtime().mode());
        assertEquals(MessageExecutionMode.VIRTUAL_THREADS, config.runtime().execution().mode());
        assertEquals(17, config.runtime().execution().virtualThreads().maxInFlight());
        assertEquals("pricing", config.clients().get("pricing").service());
    }

    @Test
    void rejectsUnknownKeys() throws Exception {
        Path file = tempDir.resolve("bad.yaml");
        Files.writeString(file, """
            ringloom:
              service:
                name: orders
                surprise: true
            """);

        assertThrows(IllegalArgumentException.class, () -> new YamlRingloomConfigLoader().load(file));
    }
}
