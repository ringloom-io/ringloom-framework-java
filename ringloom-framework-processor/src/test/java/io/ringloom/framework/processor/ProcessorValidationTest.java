// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProcessorValidationTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsDuplicateHandlerTemplateIds() throws Exception {
        // Given
        Path classes = Files.createDirectories(tempDir.resolve("dup-classes"));
        Path generated = Files.createDirectories(tempDir.resolve("dup-generated"));

        // When
        boolean success = ProcessorTestSupport.compile(
                classes, generated, List.of(ProcessorTestSupport.source("test.DuplicateHandlers", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomHandler;
                    import io.ringloom.framework.annotation.RingloomServiceComponent;
                    @RingloomServiceComponent
                    public final class DuplicateHandlers {
                      @RingloomHandler(templateId = 7) public int a() { return 0; }
                      @RingloomHandler(templateId = 7) public int b() { return 0; }
                    }
                    """)));

        // Then
        assertThat(success).isFalse();
    }

    @Test
    void rejectsInvalidScheduledMethods() throws Exception {
        // Given
        Path classes = Files.createDirectories(tempDir.resolve("bad-schedule-classes"));
        Path generated = Files.createDirectories(tempDir.resolve("bad-schedule-generated"));

        // When
        boolean success = ProcessorTestSupport.compile(
                classes, generated, List.of(ProcessorTestSupport.source("test.BadSchedule", """
                    package test;
                    import io.ringloom.framework.annotation.RingloomSchedule;
                    public final class BadSchedule {
                      @RingloomSchedule
                      void tick(String value) {}
                    }
                    """)));

        // Then
        assertThat(success).isFalse();
    }
}
