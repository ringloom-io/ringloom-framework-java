// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import io.ringloom.service.ServiceConfig;
import java.util.Objects;

public record RingloomServiceRuntimeConfig(
    String name,
    String storagePath,
    String group,
    short brokerNodeId,
    boolean blockingMode,
    int heartbeatTimeoutMillis,
    long controlBufferLength,
    long messagesBufferLength,
    boolean leaderElectionEnabled
) {
    public RingloomServiceRuntimeConfig {
        name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("service.name must not be blank");
        }
        storagePath = storagePath == null ? ServiceConfig.DEFAULT_STORAGE_PATH : storagePath;
        group = group == null ? ServiceConfig.DEFAULT_GROUP : group;
        brokerNodeId = brokerNodeId == 0 ? ServiceConfig.DEFAULT_BROKER_NODE_ID : brokerNodeId;
        heartbeatTimeoutMillis = heartbeatTimeoutMillis == 0
            ? ServiceConfig.DEFAULT_HEARTBEAT_TIMEOUT_MILLIS
            : heartbeatTimeoutMillis;
        controlBufferLength = controlBufferLength == 0
            ? ServiceConfig.DEFAULT_CONTROL_BUFFER_LENGTH
            : controlBufferLength;
        messagesBufferLength = messagesBufferLength == 0
            ? ServiceConfig.DEFAULT_MESSAGES_BUFFER_LENGTH
            : messagesBufferLength;
        if (heartbeatTimeoutMillis < 0) {
            throw new IllegalArgumentException("service.heartbeatTimeoutMillis must be non-negative");
        }
        requirePositivePowerOfTwo(controlBufferLength, "service.controlBufferLength");
        requirePositivePowerOfTwo(messagesBufferLength, "service.messagesBufferLength");
    }

    public static RingloomServiceRuntimeConfig of(String serviceName) {
        return new RingloomServiceRuntimeConfig(serviceName, null, null, (short) 0, false, 0, 0, 0, false);
    }

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
            leaderElectionEnabled
        );
    }

    private static void requirePositivePowerOfTwo(long value, String path) {
        if (value <= 0 || (value & (value - 1)) != 0) {
            throw new IllegalArgumentException(path + " must be a positive power of two");
        }
    }
}
