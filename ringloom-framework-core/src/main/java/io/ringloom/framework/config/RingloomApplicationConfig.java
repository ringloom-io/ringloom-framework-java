// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import io.ringloom.framework.config.topic.TopicsRuntimeConfig;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregates the service, runtime, serializer, client, and topic configuration for a RingLoom
 * application.
 *
 * @param service the service-level native RingLoom settings
 * @param runtime the Java runtime execution settings
 * @param serializers the configured serializer registry metadata
 * @param clients the generated client aliases available to the application
 * @param topics the persistent-topics runtime configuration
 */
public record RingloomApplicationConfig(
        RingloomServiceRuntimeConfig service,
        RingloomRuntimeConfig runtime,
        RingloomSerializerConfig serializers,
        Map<String, RingloomClientRuntimeConfig> clients,
        TopicsRuntimeConfig topics) {
    public RingloomApplicationConfig {
        service = Objects.requireNonNull(service, "service");
        runtime = runtime == null ? RingloomRuntimeConfig.defaults() : runtime;
        serializers = serializers == null ? new RingloomSerializerConfig("", Map.of()) : serializers;
        clients = clients == null ? Map.of() : Map.copyOf(clients);
        topics = topics == null ? TopicsRuntimeConfig.disabled() : topics;
    }

    /**
     * Creates the smallest valid application configuration for a service name.
     *
     * @param serviceName the RingLoom service name
     * @return a minimal application configuration using framework defaults elsewhere
     */
    public static RingloomApplicationConfig minimal(String serviceName) {
        return new RingloomApplicationConfig(RingloomServiceRuntimeConfig.of(serviceName), null, null, Map.of(), null);
    }
}
