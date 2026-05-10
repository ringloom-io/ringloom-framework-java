// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

import io.ringloom.framework.serialization.SerializerModule;
import io.ringloom.framework.serialization.SerializerRegistry;

/**
 * Serializer module placeholder for SBE-based codecs.
 */
public final class SbeSerializerModule implements SerializerModule {
    @Override
    public String name() {
        return "sbe";
    }

    @Override
    public void register(SerializerRegistry.Builder builder) {
        // Concrete generated SBE codecs register their encoders/decoders explicitly.
    }
}
