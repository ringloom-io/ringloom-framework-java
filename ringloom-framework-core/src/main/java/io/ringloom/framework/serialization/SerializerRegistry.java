// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

import java.util.Objects;
import org.agrona.collections.Object2ObjectHashMap;

/**
 * Registry of named, typed encoders, decoders, and flyweight decoders available to generated
 * code.
 *
 * <p>Lookups prefer an exact payload type match and then fall back to the most specific
 * assignable registration for the same serializer name.
 */
public final class SerializerRegistry {

    public static final SerializerRegistry EMPTY = new Builder().build();

    private final Registry<MessageEncoder<?>> encoders;
    private final Registry<MessageDecoder<?>> decoders;
    private final Registry<FlyweightDecoder<?>> flyweights;

    private SerializerRegistry(Builder builder) {
        this.encoders = new Registry<>(builder.encoders);
        this.decoders = new Registry<>(builder.decoders);
        this.flyweights = new Registry<>(builder.flyweights);
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

    private static <T> T lookup(Registry<T> values, String name, Class<?> requestedType) {
        return values.lookup(name, requestedType);
    }

    public static final class Builder {

        private final Registry<MessageEncoder<?>> encoders = new Registry<>();
        private final Registry<MessageDecoder<?>> decoders = new Registry<>();
        private final Registry<FlyweightDecoder<?>> flyweights = new Registry<>();

        public <T> Builder encoder(String name, Class<T> type, MessageEncoder<? super T> encoder) {
            encoders.put(
                    requireName(name),
                    Objects.requireNonNull(type, "type"),
                    Objects.requireNonNull(encoder, "encoder"));
            return this;
        }

        public <T> Builder decoder(String name, Class<T> type, MessageDecoder<? extends T> decoder) {
            decoders.put(
                    requireName(name),
                    Objects.requireNonNull(type, "type"),
                    Objects.requireNonNull(decoder, "decoder"));
            return this;
        }

        public <T> Builder flyweight(String name, Class<T> type, FlyweightDecoder<? extends T> decoder) {
            flyweights.put(
                    requireName(name),
                    Objects.requireNonNull(type, "type"),
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

    private static final class Registry<T> {
        private final Object2ObjectHashMap<String, Object2ObjectHashMap<Class<?>, T>> values =
                new Object2ObjectHashMap<>();

        private Registry() {}

        private Registry(Registry<? extends T> source) {
            putAll(source);
        }

        private T lookup(String name, Class<?> requestedType) {
            Object2ObjectHashMap<Class<?>, T> bucket = values.get(name);
            if (bucket == null) {
                return null;
            }
            T exact = bucket.get(requestedType);
            if (exact != null) {
                return exact;
            }
            Class<?> bestType = null;
            T best = null;
            for (java.util.Map.Entry<Class<?>, T> entry : bucket.entrySet()) {
                Class<?> candidate = entry.getKey();
                if (!candidate.isAssignableFrom(requestedType)) {
                    continue;
                }
                if (bestType == null || bestType.isAssignableFrom(candidate)) {
                    bestType = candidate;
                    best = entry.getValue();
                }
            }
            return best;
        }

        private void put(String name, Class<?> type, T value) {
            Object2ObjectHashMap<Class<?>, T> bucket = values.get(name);
            if (bucket == null) {
                bucket = new Object2ObjectHashMap<>();
                values.put(name, bucket);
            }
            bucket.put(type, value);
        }

        private void putAll(Registry<? extends T> source) {
            for (var sourceEntry : source.values.entrySet()) {
                Object2ObjectHashMap<Class<?>, T> targetBucket = values.get(sourceEntry.getKey());
                if (targetBucket == null) {
                    targetBucket = new Object2ObjectHashMap<>();
                    values.put(sourceEntry.getKey(), targetBucket);
                }
                targetBucket.putAll(sourceEntry.getValue());
            }
        }
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("serializer name must not be blank");
        }
        return name;
    }
}
