// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.metrics;

/**
 * Native-backed application gauge registered through the RingLoom metrics facade.
 */
public interface RingloomGauge {
    /**
     * Returns the native metric id.
     *
     * @return the native metric id
     */
    int id();

    /**
     * Sets the gauge value.
     *
     * @param value the new gauge value
     */
    void set(long value);
}
