// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.fory;

import io.ringloom.framework.serialization.SerializerModule;
import io.ringloom.framework.serialization.SerializerRegistry;
import java.util.Objects;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;

/**
 * Serializer module for Apache Fory integrations.
 */
public final class ForySerializerModule implements SerializerModule {
    @Override
    public String name() {
        return "fory";
    }

    @Override
    public void register(SerializerRegistry.Builder builder) {
        register(builder, ForySerializerConfig.defaults());
    }

    /**
     * Registers a configured Apache Fory codec under the {@code fory} serializer name.
     *
     * @param builder the serializer registry builder
     * @param config the Fory serializer configuration
     */
    public void register(SerializerRegistry.Builder builder, ForySerializerConfig config) {
        Objects.requireNonNull(builder, "builder");
        ForyMessageCodec<Object> codec =
                new ForyMessageCodec<>(createFory(config), Object.class, config.maxPayloadBytes());
        builder.encoder(name(), codec).decoder(name(), codec);
    }

    /**
     * Creates a thread-safe Apache Fory runtime for the supplied configuration.
     *
     * @param config the Fory serializer configuration
     * @return a configured thread-safe Fory runtime
     */
    public ThreadSafeFory createFory(ForySerializerConfig config) {
        Objects.requireNonNull(config, "config");
        ThreadSafeFory fory = Fory.builder()
                .withLanguage(Language.JAVA)
                .requireClassRegistration(config.requireRegistration())
                .withCompatibleMode(
                        config.compatibleMode() ? CompatibleMode.COMPATIBLE : CompatibleMode.SCHEMA_CONSISTENT)
                .withRefTracking(config.referenceTracking())
                .withBufferSizeLimitBytes(config.maxPayloadBytes())
                .buildThreadSafeFory();
        for (String typeName : config.registeredTypes()) {
            fory.register(typeName);
        }
        fory.ensureSerializersCompiled();
        return fory;
    }
}
