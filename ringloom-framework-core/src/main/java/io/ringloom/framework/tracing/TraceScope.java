// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.tracing;

public interface TraceScope extends AutoCloseable {
    @Override
    void close();
}
