// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

public enum MessageExecutionMode {
    CONSUMER_THREAD,
    PARTITIONED_WORKERS,
    VIRTUAL_THREADS
}
