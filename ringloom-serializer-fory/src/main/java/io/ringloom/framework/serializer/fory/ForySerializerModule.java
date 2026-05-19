// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.fory;

import io.ringloom.framework.serialization.SerializerModule;
import io.ringloom.framework.serialization.SerializerRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;

/**
 * Serializer module for Apache Fory integrations.
 */
public final class ForySerializerModule implements SerializerModule {

    private static final String SERIALIZER_NAME = "fory";

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
        register(builder, config, List.of());
    }

    /**
     * Registers a configured Apache Fory codec under the {@code fory} serializer name and registers
     * the generated payload type set with deterministic ids.
     *
     * @param builder the serializer registry builder
     * @param config the Fory serializer configuration
     * @param generatedTypes generated payload types known at compile time
     */
    public void register(
            SerializerRegistry.Builder builder, ForySerializerConfig config, Collection<Class<?>> generatedTypes) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(generatedTypes, "generatedTypes");
        ForyMessageCodec<Object> codec =
                new ForyMessageCodec<>(createFory(config, generatedTypes), Object.class, config.maxPayloadBytes());
        builder.encoder(SERIALIZER_NAME, Object.class, codec).decoder(SERIALIZER_NAME, Object.class, codec);
    }

    /**
     * Creates a thread-safe Apache Fory runtime for the supplied configuration.
     *
     * @param config the Fory serializer configuration
     * @return a configured thread-safe Fory runtime
     */
    public ThreadSafeFory createFory(ForySerializerConfig config) {
        return createFory(config, List.of());
    }

    /**
     * Creates a thread-safe Apache Fory runtime for the supplied configuration and generated types.
     *
     * @param config the Fory serializer configuration
     * @param generatedTypes generated payload types known at compile time
     * @return a configured thread-safe Fory runtime
     */
    public ThreadSafeFory createFory(ForySerializerConfig config, Collection<Class<?>> generatedTypes) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(generatedTypes, "generatedTypes");
        ThreadSafeFory fory = Fory.builder()
                .withLanguage(Language.JAVA)
                .requireClassRegistration(config.requireRegistration())
                .withCompatibleMode(
                        config.compatibleMode() ? CompatibleMode.COMPATIBLE : CompatibleMode.SCHEMA_CONSISTENT)
                .withRefTracking(config.referenceTracking())
                .withBufferSizeLimitBytes(config.maxPayloadBytes())
                .buildThreadSafeFory();
        for (RegisteredType type : registeredTypes(config, generatedTypes)) {
            if (type.type() == null) {
                fory.register(type.name(), type.id());
            } else {
                fory.register(type.type(), type.id());
            }
        }
        fory.ensureSerializersCompiled();
        return fory;
    }

    private static List<RegisteredType> registeredTypes(
            ForySerializerConfig config, Collection<Class<?>> generatedTypes) {
        List<RegisteredType> types = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Class<?> type : generatedTypes) {
            if (type == null || !names.add(type.getName())) {
                continue;
            }
            types.add(new RegisteredType(type.getName(), type, stableTypeId(type.getName())));
        }
        for (String typeName : config.registeredTypes()) {
            if (names.add(typeName)) {
                types.add(new RegisteredType(typeName, null, stableTypeId(typeName)));
            }
        }
        types.sort(Comparator.comparing(RegisteredType::name));
        Set<Integer> ids = new HashSet<>();
        for (RegisteredType type : types) {
            if (!ids.add(type.id())) {
                throw new IllegalArgumentException("Fory generated type id collision for " + type.name());
            }
        }
        return types;
    }

    private static int stableTypeId(String typeName) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < typeName.length(); i++) {
            hash ^= typeName.charAt(i);
            hash *= 0x01000193;
        }
        return 100_000 + Math.floorMod(hash, 900_000_000);
    }

    private record RegisteredType(String name, Class<?> type, int id) {}
}
