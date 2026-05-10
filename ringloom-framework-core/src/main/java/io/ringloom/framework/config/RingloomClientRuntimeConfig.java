// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import io.ringloom.framework.annotation.RoutingMode;
import java.util.Objects;

public record RingloomClientRuntimeConfig(String alias, String service, RoutingMode routing, String serializer) {
    public RingloomClientRuntimeConfig {
        alias = Objects.requireNonNull(alias, "alias");
        service = Objects.requireNonNull(service, "service");
        if (alias.isBlank()) {
            throw new IllegalArgumentException("client alias must not be blank");
        }
        if (service.isBlank()) {
            throw new IllegalArgumentException("client service must not be blank");
        }
        routing = Objects.requireNonNullElse(routing, RoutingMode.LOAD_BALANCED);
        serializer = serializer == null ? "" : serializer;
    }
}
