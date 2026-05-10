// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.fory;

import java.util.List;

/**
 * Configuration for an Apache Fory serializer integration.
 *
 * @param requireRegistration whether types must be registered up front
 * @param compatibleMode whether compatibility mode is enabled
 * @param referenceTracking whether object reference tracking is enabled
 * @param registeredTypes the explicitly registered type names
 */
public record ForySerializerConfig(
        boolean requireRegistration, boolean compatibleMode, boolean referenceTracking, List<String> registeredTypes) {
    public ForySerializerConfig {
        registeredTypes = registeredTypes == null ? List.of() : List.copyOf(registeredTypes);
    }

    /**
     * Returns the default Fory serializer configuration.
     *
     * @return the default Fory settings
     */
    public static ForySerializerConfig defaults() {
        return new ForySerializerConfig(true, false, false, List.of());
    }
}
