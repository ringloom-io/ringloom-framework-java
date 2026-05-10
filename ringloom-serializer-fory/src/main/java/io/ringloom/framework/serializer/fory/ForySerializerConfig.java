// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.fory;

import java.util.List;

public record ForySerializerConfig(
    boolean requireRegistration,
    boolean compatibleMode,
    boolean referenceTracking,
    List<String> registeredTypes
) {
    public ForySerializerConfig {
        registeredTypes = registeredTypes == null ? List.of() : List.copyOf(registeredTypes);
    }

    public static ForySerializerConfig defaults() {
        return new ForySerializerConfig(true, false, false, List.of());
    }
}
