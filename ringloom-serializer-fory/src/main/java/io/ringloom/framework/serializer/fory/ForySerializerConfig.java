// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.fory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration for an Apache Fory serializer integration.
 *
 * @param requireRegistration whether types must be registered up front
 * @param compatibleMode whether compatibility mode is enabled
 * @param referenceTracking whether object reference tracking is enabled
 * @param registeredTypes the explicitly registered type names
 * @param maxPayloadBytes the maximum encoded payload size
 */
public record ForySerializerConfig(
        boolean requireRegistration,
        boolean compatibleMode,
        boolean referenceTracking,
        List<String> registeredTypes,
        int maxPayloadBytes) {
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 1024 * 1024;

    public ForySerializerConfig {
        registeredTypes = registeredTypes == null ? List.of() : List.copyOf(registeredTypes);
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("fory.maxPayloadBytes must be positive");
        }
    }

    /**
     * Returns the default Fory serializer configuration.
     *
     * @return the default Fory settings
     */
    public static ForySerializerConfig defaults() {
        return new ForySerializerConfig(true, false, false, List.of(), DEFAULT_MAX_PAYLOAD_BYTES);
    }

    /**
     * Creates a Fory configuration from a raw serializer entry map.
     *
     * @param values the raw configuration values
     * @return the parsed Fory configuration
     */
    public static ForySerializerConfig from(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return defaults();
        }
        requireKeys(values);
        boolean requireRegistration = booleanValue(values.get("requireRegistration"), true, "requireRegistration");
        boolean compatibleMode = booleanValue(values.get("compatibleMode"), false, "compatibleMode");
        boolean referenceTracking = booleanValue(values.get("referenceTracking"), false, "referenceTracking");
        int maxPayloadBytes = intValue(values.get("maxPayloadBytes"), DEFAULT_MAX_PAYLOAD_BYTES, "maxPayloadBytes");
        return new ForySerializerConfig(
                requireRegistration,
                compatibleMode,
                referenceTracking,
                stringList(values.get("registeredTypes")),
                maxPayloadBytes);
    }

    private static boolean booleanValue(Object value, boolean defaultValue, String name) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException("fory." + name + " must be boolean");
        }
        return result;
    }

    private static int intValue(Object value, int defaultValue, String name) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number result)) {
            throw new IllegalArgumentException("fory." + name + " must be an integer");
        }
        return result.intValue();
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("fory.registeredTypes must be a list");
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object entry : values) {
            if (!(entry instanceof String typeName) || typeName.isBlank()) {
                throw new IllegalArgumentException("fory.registeredTypes entries must be non-blank strings");
            }
            result.add(typeName);
        }
        return result;
    }

    private static void requireKeys(Map<String, Object> values) {
        Set<String> allowed = Set.of(
                "type",
                "requireRegistration",
                "compatibleMode",
                "referenceTracking",
                "registeredTypes",
                "maxPayloadBytes");
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("unknown Fory serializer config key " + key);
            }
        }
    }
}
