// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import java.util.Map;
import java.util.Objects;

public record RingloomApplicationConfig(
    RingloomServiceRuntimeConfig service,
    RingloomRuntimeConfig runtime,
    RingloomSerializerConfig serializers,
    Map<String, RingloomClientRuntimeConfig> clients
) {
    public RingloomApplicationConfig {
        service = Objects.requireNonNull(service, "service");
        runtime = runtime == null ? RingloomRuntimeConfig.defaults() : runtime;
        serializers = serializers == null ? new RingloomSerializerConfig("", Map.of()) : serializers;
        clients = clients == null ? Map.of() : Map.copyOf(clients);
    }

    public static RingloomApplicationConfig minimal(String serviceName) {
        return new RingloomApplicationConfig(RingloomServiceRuntimeConfig.of(serviceName), null, null, Map.of());
    }
}
