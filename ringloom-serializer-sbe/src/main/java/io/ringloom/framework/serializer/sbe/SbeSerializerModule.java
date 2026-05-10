// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

import io.ringloom.framework.serialization.SerializerModule;
import io.ringloom.framework.serialization.SerializerRegistry;

/**
 * Serializer module for SBE-based codecs.
 *
 * <p>SBE codecs are template-specific and are normally wired by generated application code using
 * {@link SbeCodecFactory}; this module keeps the serializer name discoverable without registering
 * global flyweight singletons.
 */
public final class SbeSerializerModule implements SerializerModule {
    @Override
    public String name() {
        return "sbe";
    }

    @Override
    public void register(SerializerRegistry.Builder builder) {
        register(builder, SbeConfig.defaults());
    }

    /**
     * Registers SBE support for a serializer registry builder.
     *
     * @param builder the registry builder
     * @param config the SBE configuration
     */
    public void register(SerializerRegistry.Builder builder, SbeConfig config) {
        java.util.Objects.requireNonNull(builder, "builder");
        java.util.Objects.requireNonNull(config, "config");
    }
}
