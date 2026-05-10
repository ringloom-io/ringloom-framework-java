// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class RingloomApplicationConfigTest {
    @Test
    void appliesServiceDefaults() {
        // Given / When
        RingloomApplicationConfig config = RingloomApplicationConfig.minimal("orders");

        // Then
        assertThat(config.service().name()).isEqualTo("orders");
        assertThat(config.runtime().mode()).isEqualTo(RuntimeMode.DEDICATED);
        assertThat(config.service().controlBufferLength()).isEqualTo(65_536L);
    }

    @Test
    void rejectsInvalidRingSizes() {
        // Given / When / Then
        assertThatThrownBy(() -> new RingloomServiceRuntimeConfig("bad", null, null, (short) 0, false, 0, 3, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("service.controlBufferLength must be a positive power of two");
    }
}
