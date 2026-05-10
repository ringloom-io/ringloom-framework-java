// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import java.util.Map;
import java.util.Objects;

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

    public Map<String, Object> entry(String name) {
        return entries.get(Objects.requireNonNull(name, "name"));
    }
}
