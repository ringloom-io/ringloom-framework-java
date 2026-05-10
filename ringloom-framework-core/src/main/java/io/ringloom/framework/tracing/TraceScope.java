// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.tracing;

/**
 * Represents a tracing scope that should be closed when an instrumented operation ends.
 */
public interface TraceScope extends AutoCloseable {
    /**
     * Ends the tracing scope.
     */
    @Override
    void close();
}
