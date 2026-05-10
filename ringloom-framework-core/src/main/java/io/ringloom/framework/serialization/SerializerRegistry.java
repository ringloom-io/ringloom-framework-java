// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry of named encoders, decoders, and flyweight decoders available to generated code.
 */
public final class SerializerRegistry {
    public static final SerializerRegistry EMPTY = new Builder().build();

    private final Map<String, MessageEncoder<?>> encoders;
    private final Map<String, MessageDecoder<?>> decoders;
    private final Map<String, FlyweightDecoder<?>> flyweights;

    private SerializerRegistry(Builder builder) {
        this.encoders = Map.copyOf(builder.encoders);
        this.decoders = Map.copyOf(builder.decoders);
        this.flyweights = Map.copyOf(builder.flyweights);
    }

    public static Builder builder() {
        return new Builder();
    }

    @SuppressWarnings("unchecked")
    public <T> MessageEncoder<T> encoder(String name, Class<T> type) {
        return (MessageEncoder<T>) encoders.get(Objects.requireNonNull(name, "name"));
    }

    @SuppressWarnings("unchecked")
    public <T> MessageDecoder<T> decoder(String name, Class<T> type) {
        return (MessageDecoder<T>) decoders.get(Objects.requireNonNull(name, "name"));
    }

    @SuppressWarnings("unchecked")
    public <T> FlyweightDecoder<T> flyweight(String name, Class<T> type) {
        return (FlyweightDecoder<T>) flyweights.get(Objects.requireNonNull(name, "name"));
    }

    public boolean contains(String name) {
        return encoders.containsKey(name) || decoders.containsKey(name) || flyweights.containsKey(name);
    }

    public static final class Builder {
        private final Map<String, MessageEncoder<?>> encoders = new HashMap<>();
        private final Map<String, MessageDecoder<?>> decoders = new HashMap<>();
        private final Map<String, FlyweightDecoder<?>> flyweights = new HashMap<>();

        public Builder encoder(String name, MessageEncoder<?> encoder) {
            encoders.put(requireName(name), Objects.requireNonNull(encoder, "encoder"));
            return this;
        }

        public Builder decoder(String name, MessageDecoder<?> decoder) {
            decoders.put(requireName(name), Objects.requireNonNull(decoder, "decoder"));
            return this;
        }

        public Builder flyweight(String name, FlyweightDecoder<?> decoder) {
            flyweights.put(requireName(name), Objects.requireNonNull(decoder, "decoder"));
            return this;
        }

        public Builder module(SerializerModule module) {
            Objects.requireNonNull(module, "module").register(this);
            return this;
        }

        public SerializerRegistry build() {
            return new SerializerRegistry(this);
        }

        private static String requireName(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("serializer name must not be blank");
            }
            return name;
        }
    }
}
