// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RingloomApplicationConfigTest {
    @Test
    void appliesServiceDefaults() {
        RingloomApplicationConfig config = RingloomApplicationConfig.minimal("orders");

        assertEquals("orders", config.service().name());
        assertEquals(RuntimeMode.DEDICATED, config.runtime().mode());
        assertEquals(65_536L, config.service().controlBufferLength());
    }

    @Test
    void rejectsInvalidRingSizes() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new RingloomServiceRuntimeConfig("bad", null, null, (short) 0, false, 0, 3, 0, false)
        );
    }
}
