// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProviderSourceGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesClientDispatcherAndApplication() throws Exception {
        // Given
        Path classes = Files.createDirectories(tempDir.resolve("classes"));
        Path generated = Files.createDirectories(tempDir.resolve("generated"));

        // When
        boolean success = ProcessorTestSupport.compile(
                classes,
                generated,
                List.of(
                        ProcessorTestSupport.source("test.OrdersApp", ProcessorTestSupport.ORDERS_APP_SRC),
                        ProcessorTestSupport.source("test.PricingClient", ProcessorTestSupport.PRICING_CLIENT_SRC),
                        ProcessorTestSupport.source("test.Handlers", ProcessorTestSupport.HANDLERS_SRC)));

        // Then
        assertThat(success).isTrue();
        assertThat(Files.readString(generated.resolve("test/OrdersApp_RingloomApplicationProvider.java")))
                .contains("class OrdersApp_RingloomApplicationProvider");
        assertThat(classes.resolve(
                        "META-INF/services/io.ringloom.framework.generated.GeneratedRingloomApplicationProvider"))
                .exists();
    }
}
