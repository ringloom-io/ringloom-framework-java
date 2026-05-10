// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import io.ringloom.service.ServiceConfig;
import java.util.Objects;

/**
 * Configures the native RingLoom service instance started by the Java runtime.
 *
 * <p>The compact constructor normalizes omitted values to native defaults and validates that ring
 * buffer sizes are positive powers of two.
 *
 * @param name the RingLoom service name
 * @param storagePath the path used by the native service for storage
 * @param group the service group name
 * @param brokerNodeId the broker node id to connect to
 * @param blockingMode whether native APIs should block
 * @param heartbeatTimeoutMillis the heartbeat timeout in milliseconds
 * @param controlBufferLength the control-ring size in bytes
 * @param messagesBufferLength the messages-ring size in bytes
 * @param leaderElectionEnabled whether leader election is enabled
 */
public record RingloomServiceRuntimeConfig(
        String name,
        String storagePath,
        String group,
        short brokerNodeId,
        boolean blockingMode,
        int heartbeatTimeoutMillis,
        long controlBufferLength,
        long messagesBufferLength,
        boolean leaderElectionEnabled) {
    public RingloomServiceRuntimeConfig {
        name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("service.name must not be blank");
        }
        storagePath = storagePath == null ? ServiceConfig.DEFAULT_STORAGE_PATH : storagePath;
        group = group == null ? ServiceConfig.DEFAULT_GROUP : group;
        brokerNodeId = brokerNodeId == 0 ? ServiceConfig.DEFAULT_BROKER_NODE_ID : brokerNodeId;
        heartbeatTimeoutMillis =
                heartbeatTimeoutMillis == 0 ? ServiceConfig.DEFAULT_HEARTBEAT_TIMEOUT_MILLIS : heartbeatTimeoutMillis;
        controlBufferLength =
                controlBufferLength == 0 ? ServiceConfig.DEFAULT_CONTROL_BUFFER_LENGTH : controlBufferLength;
        messagesBufferLength =
                messagesBufferLength == 0 ? ServiceConfig.DEFAULT_MESSAGES_BUFFER_LENGTH : messagesBufferLength;
        if (heartbeatTimeoutMillis < 0) {
            throw new IllegalArgumentException("service.heartbeatTimeoutMillis must be non-negative");
        }
        requirePositivePowerOfTwo(controlBufferLength, "service.controlBufferLength");
        requirePositivePowerOfTwo(messagesBufferLength, "service.messagesBufferLength");
    }

    /**
     * Creates a service configuration using the supplied name and framework defaults elsewhere.
     *
     * @param serviceName the RingLoom service name
     * @return a defaulted service configuration
     */
    public static RingloomServiceRuntimeConfig of(String serviceName) {
        return new RingloomServiceRuntimeConfig(serviceName, null, null, (short) 0, false, 0, 0, 0, false);
    }

    /**
     * Converts this Java configuration into the low-level native {@link ServiceConfig}.
     *
     * @return the native service configuration
     */
    public ServiceConfig toLowLevelConfig() {
        return new ServiceConfig(
                name,
                storagePath,
                group,
                brokerNodeId,
                blockingMode,
                heartbeatTimeoutMillis,
                controlBufferLength,
                messagesBufferLength,
                leaderElectionEnabled);
    }

    private static void requirePositivePowerOfTwo(long value, String path) {
        if (value <= 0 || (value & (value - 1)) != 0) {
            throw new IllegalArgumentException(path + " must be a positive power of two");
        }
    }
}
