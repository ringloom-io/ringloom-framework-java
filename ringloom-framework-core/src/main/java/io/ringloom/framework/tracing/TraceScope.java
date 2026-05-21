// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.tracing;

/**
 * Represents a tracing scope that should be closed when an instrumented operation ends.
 */
public interface TraceScope extends AutoCloseable {
    /**
     * Records the RingLoom completion status before the scope is closed.
     *
     * @param status the RingLoom status code
     */
    default void complete(int status) {}

    /**
     * Ends the tracing scope.
     */
    @Override
    void close();
}
