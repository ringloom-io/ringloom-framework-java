// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.fory;

import io.ringloom.framework.serialization.SerializerModule;
import io.ringloom.framework.serialization.SerializerRegistry;

public final class ForySerializerModule implements SerializerModule {
    @Override
    public String name() {
        return "fory";
    }

    @Override
    public void register(SerializerRegistry.Builder builder) {
        // The concrete Apache Fory adapter is optional and is registered by applications.
    }
}
