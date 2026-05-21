// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.metrics;

/**
 * Native-backed application counter registered through the RingLoom metrics facade.
 */
public interface RingloomCounter {
    /**
     * Returns the native metric id.
     *
     * @return the native metric id
     */
    int id();

    /**
     * Increments the counter by one.
     */
    void increment();

    /**
     * Adds a delta to the counter.
     *
     * @param delta the value to add
     */
    void add(long delta);

    /**
     * Sets the counter value.
     *
     * @param value the new counter value
     */
    void set(long value);
}
