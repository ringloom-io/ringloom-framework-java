// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import java.util.Map;
import java.util.Objects;

/**
 * Describes the serializer names and configuration entries available to the runtime.
 *
 * @param defaultSerializer the serializer name used when an API does not specify one explicitly
 * @param entries arbitrary per-serializer configuration maps keyed by serializer name
 */
public record RingloomSerializerConfig(String defaultSerializer, Map<String, Map<String, Object>> entries) {
    public RingloomSerializerConfig {
        defaultSerializer = defaultSerializer == null ? "" : defaultSerializer;
        entries = entries == null ? Map.of() : Map.copyOf(entries);
        for (String name : entries.keySet()) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("serializer names must not be blank");
            }
        }
    }

    /**
     * Returns the raw configuration entry for a serializer name.
     *
     * @param name the serializer name
     * @return the configured entry map, or {@code null} when none exists
     */
    public Map<String, Object> entry(String name) {
        return entries.get(Objects.requireNonNull(name, "name"));
    }
}
