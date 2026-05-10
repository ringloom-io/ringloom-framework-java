// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

public enum IdleStrategyKind {
    BUSY_SPIN,
    YIELDING,
    SLEEPING,
    BACKOFF,
    NO_OP
}
