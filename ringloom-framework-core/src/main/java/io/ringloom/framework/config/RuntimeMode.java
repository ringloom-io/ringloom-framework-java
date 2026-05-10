// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

/**
 * Defines who owns the runtime event loops.
 */
public enum RuntimeMode {
    DEDICATED,
    SHARED,
    EXTERNAL
}
