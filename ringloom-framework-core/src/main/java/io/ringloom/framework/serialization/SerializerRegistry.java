// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry of named, typed encoders, decoders, and flyweight decoders available to generated
 * code.
 *
 * <p>Lookups prefer an exact payload type match and then fall back to the most specific
 * assignable registration for the same serializer name.
 */
public final class SerializerRegistry {

    public static final SerializerRegistry EMPTY = new Builder().build();

    private final Map<Key, MessageEncoder<?>> encoders;
    private final Map<Key, MessageDecoder<?>> decoders;
    private final Map<Key, FlyweightDecoder<?>> flyweights;

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
        return (MessageEncoder<T>) lookup(encoders, requireName(name), Objects.requireNonNull(type, "type"));
    }

    @SuppressWarnings("unchecked")
    public <T> MessageDecoder<T> decoder(String name, Class<T> type) {
        return (MessageDecoder<T>) lookup(decoders, requireName(name), Objects.requireNonNull(type, "type"));
    }

    @SuppressWarnings("unchecked")
    public <T> FlyweightDecoder<T> flyweight(String name, Class<T> type) {
        return (FlyweightDecoder<T>) lookup(flyweights, requireName(name), Objects.requireNonNull(type, "type"));
    }

    /**
     * Copies all registered serializers into another builder.
     *
     * @param builder the target builder
     */
    public void registerInto(Builder builder) {
        Objects.requireNonNull(builder, "builder");
        builder.encoders.putAll(encoders);
        builder.decoders.putAll(decoders);
        builder.flyweights.putAll(flyweights);
    }

    private static <T> T lookup(Map<Key, T> values, String name, Class<?> requestedType) {
        T exact = values.get(new Key(name, requestedType));
        if (exact != null) {
            return exact;
        }

        Key bestMatch = null;
        for (Key candidate : values.keySet()) {
            if (!candidate.name().equals(name) || !candidate.type().isAssignableFrom(requestedType)) {
                continue;
            }
            if (bestMatch == null || bestMatch.type().isAssignableFrom(candidate.type())) {
                bestMatch = candidate;
            }
        }
        return bestMatch == null ? null : values.get(bestMatch);
    }

    public static final class Builder {

        private final Map<Key, MessageEncoder<?>> encoders = new HashMap<>();
        private final Map<Key, MessageDecoder<?>> decoders = new HashMap<>();
        private final Map<Key, FlyweightDecoder<?>> flyweights = new HashMap<>();

        public <T> Builder encoder(String name, Class<T> type, MessageEncoder<? super T> encoder) {
            encoders.put(
                    new Key(requireName(name), Objects.requireNonNull(type, "type")),
                    Objects.requireNonNull(encoder, "encoder"));
            return this;
        }

        public <T> Builder decoder(String name, Class<T> type, MessageDecoder<? extends T> decoder) {
            decoders.put(
                    new Key(requireName(name), Objects.requireNonNull(type, "type")),
                    Objects.requireNonNull(decoder, "decoder"));
            return this;
        }

        public <T> Builder flyweight(String name, Class<T> type, FlyweightDecoder<? extends T> decoder) {
            flyweights.put(
                    new Key(requireName(name), Objects.requireNonNull(type, "type")),
                    Objects.requireNonNull(decoder, "decoder"));
            return this;
        }

        public Builder module(SerializerModule module) {
            Objects.requireNonNull(module, "module").register(this);
            return this;
        }

        public SerializerRegistry build() {
            return new SerializerRegistry(this);
        }
    }

    private record Key(String name, Class<?> type) {}

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("serializer name must not be blank");
        }
        return name;
    }
}
