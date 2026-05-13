// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.common;

import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import io.ringloom.framework.serialization.SerializerModule;
import io.ringloom.framework.serializer.fory.ForySerializerConfig;
import io.ringloom.framework.serializer.fory.ForySerializerModule;
import java.util.List;

@Factory
public final class OrderManagementModule {

    @Bean
    SerializerModule forySerializerModule() {
        return builder -> new ForySerializerModule()
                .register(
                        builder,
                        new ForySerializerConfig(
                                false, false, false, List.of(), ForySerializerConfig.DEFAULT_MAX_PAYLOAD_BYTES));
    }
}
