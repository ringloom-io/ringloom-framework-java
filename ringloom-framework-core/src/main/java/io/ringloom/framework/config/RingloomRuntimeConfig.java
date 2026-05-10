// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import java.util.Objects;

public record RingloomRuntimeConfig(
    RuntimeMode mode,
    RingloomEventLoopConfig control,
    RingloomEventLoopConfig messages,
    MessageExecutionConfig execution,
    RequestRuntimeConfig requests,
    boolean shutdownHook
) {
    public RingloomRuntimeConfig {
        mode = Objects.requireNonNullElse(mode, RuntimeMode.DEDICATED);
        control = control == null ? RingloomEventLoopConfig.defaults() : control;
        messages = messages == null ? RingloomEventLoopConfig.defaults() : messages;
        execution = execution == null ? MessageExecutionConfig.consumerThread() : execution;
        requests = requests == null ? RequestRuntimeConfig.defaults() : requests;
    }

    public static RingloomRuntimeConfig defaults() {
        return new RingloomRuntimeConfig(RuntimeMode.DEDICATED, null, null, null, null, true);
    }
}
