// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

/**
 * Enumerates the built-in idle strategies available for RingLoom event loops.
 */
public enum IdleStrategyKind {
    BUSY_SPIN,
    YIELDING,
    SLEEPING,
    BACKOFF,
    NO_OP
}
