// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import java.util.Objects;

/**
 * Configures the Java-side runtime that wraps the native RingLoom service.
 *
 * @param mode the event-loop ownership mode
 * @param control the control-loop settings
 * @param messages the message-loop settings
 * @param execution the inbound message execution policy
 * @param scheduler the control-loop scheduler settings
 * @param requests the request/response tracking settings
 * @param shutdownHook whether the runtime should install a JVM shutdown hook
 */
public record RingloomRuntimeConfig(
        RuntimeMode mode,
        RingloomEventLoopConfig control,
        RingloomEventLoopConfig messages,
        MessageExecutionConfig execution,
        SchedulerRuntimeConfig scheduler,
        RequestRuntimeConfig requests,
        boolean shutdownHook) {
    public RingloomRuntimeConfig {
        mode = Objects.requireNonNullElse(mode, RuntimeMode.DEDICATED);
        control = control == null ? RingloomEventLoopConfig.defaults() : control;
        messages = messages == null ? RingloomEventLoopConfig.defaults() : messages;
        validateEventLoopAffinity(mode, control, messages);
        execution = execution == null ? MessageExecutionConfig.consumerThread() : execution;
        scheduler = scheduler == null ? SchedulerRuntimeConfig.defaults() : scheduler;
        requests = requests == null ? RequestRuntimeConfig.defaults() : requests;
    }

    /**
     * Returns the default Java runtime configuration.
     *
     * @return the framework defaults for runtime execution
     */
    public static RingloomRuntimeConfig defaults() {
        return new RingloomRuntimeConfig(RuntimeMode.DEDICATED, null, null, null, null, null, true);
    }

    private static void validateEventLoopAffinity(
            RuntimeMode mode, RingloomEventLoopConfig control, RingloomEventLoopConfig messages) {
        if (mode == RuntimeMode.EXTERNAL && (control.cpuCore() != null || messages.cpuCore() != null)) {
            throw new IllegalArgumentException("event-loop cpuCore cannot be configured in external runtime mode");
        }
        if (mode == RuntimeMode.SHARED
                && control.cpuCore() != null
                && messages.cpuCore() != null
                && !control.cpuCore().equals(messages.cpuCore())) {
            throw new IllegalArgumentException(
                    "shared runtime mode uses one event-loop thread, so control.cpuCore and messages.cpuCore must match");
        }
    }
}
