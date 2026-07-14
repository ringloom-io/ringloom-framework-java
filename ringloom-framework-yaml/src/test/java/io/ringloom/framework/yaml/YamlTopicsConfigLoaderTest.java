// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.service.TopicStart;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class YamlTopicsConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void parsesTopicsSection() throws Exception {
        Path file = tempDir.resolve("topics.yaml");
        Files.writeString(file, """
                ringloom:
                  service:
                    name: topics-svc
                  runtime:
                    mode: dedicated
                  serializers:
                    default: fory
                  clients: {}
                  topics:
                    enabled: true
                    coalesceWithMessages: false
                    prefetcher:
                      cpuAffinity: 3
                      pollLimit: 32
                      intervalMicros: 500
                    publisherDefaults:
                      rollScheme: FAST_HOURLY
                      retentionCycles: 2
                    handlers:
                      quotes:
                        topic: quotes
                        start: latest
                        serializer: fory
                        partitionKey: symbol
                """);

        var config = new YamlRingloomConfigLoader().load(file);

        assertThat(config.topics().enabled()).isTrue();
        assertThat(config.topics().coalesceWithMessages()).isFalse();
        assertThat(config.topics().prefetcher().cpuAffinity()).isEqualTo(3);
        assertThat(config.topics().prefetcher().pollLimit()).isEqualTo(32);
        assertThat(config.topics().prefetcher().intervalMicros()).isEqualTo(500L);
        assertThat(config.topics().publisherDefaults().rollScheme()).isEqualTo("FAST_HOURLY");
        assertThat(config.topics().publisherDefaults().retentionCycles()).isEqualTo(2);
        assertThat(config.topics().handlers()).hasSize(1);
        assertThat(config.topics().handlers().get("quotes").topic()).isEqualTo("quotes");
        assertThat(config.topics().handlers().get("quotes").start()).isEqualTo(TopicStart.LATEST);
        assertThat(config.topics().handlers().get("quotes").serializer()).isEqualTo("fory");
        assertThat(config.topics().handlers().get("quotes").partitionKey()).isEqualTo("symbol");
    }

    @Test
    void absentTopicsSectionDefaultsToDisabled() throws Exception {
        Path file = tempDir.resolve("no-topics.yaml");
        Files.writeString(file, """
                ringloom:
                  service:
                    name: plain
                  runtime:
                    mode: dedicated
                  serializers:
                    default: fory
                  clients: {}
                """);

        var config = new YamlRingloomConfigLoader().load(file);

        assertThat(config.topics().enabled()).isFalse();
        assertThat(config.topics().handlers()).isEmpty();
    }

    @Test
    void rejectsUnknownTopicsKeys() throws Exception {
        Path file = tempDir.resolve("bad-topics.yaml");
        Files.writeString(file, """
                ringloom:
                  service:
                    name: bad
                  runtime:
                    mode: dedicated
                  serializers:
                    default: fory
                  clients: {}
                  topics:
                    enabled: true
                    bogus: true
                """);

        assertThatThrownBy(() -> new YamlRingloomConfigLoader().load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown key ringloom.topics.bogus");
    }
}
