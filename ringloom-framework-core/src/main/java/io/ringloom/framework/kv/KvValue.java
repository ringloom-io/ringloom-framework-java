// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

/**
 * Reusable result holder for status-profile KV gets. Not thread-safe; reuse per thread.
 *
 * @param <V> the value type
 */
public final class KvValue<V> {
    private static final int OK = 0;

    private V value;
    private int length;
    private int status = -1;

    /** Returns the decoded value; {@code null} unless the last get returned {@code OK}. */
    public V value() {
        return value;
    }

    /** Returns the encoded length of the last {@code OK} value, otherwise 0. */
    public int length() {
        return length;
    }

    /** Returns the status of the last get. */
    public int status() {
        return status;
    }

    /** Returns whether the last get returned {@code OK}. */
    public boolean isOk() {
        return status == OK;
    }

    /**
     * Records a successful get.
     *
     * @param value the decoded value
     * @param length the encoded value length
     * @return this holder
     */
    public KvValue<V> ok(V value, int length) {
        this.value = value;
        this.length = length;
        this.status = OK;
        return this;
    }

    /**
     * Records a failed or empty get; clears the value.
     *
     * @param status a non-OK status
     * @return this holder
     */
    public KvValue<V> fail(int status) {
        this.value = null;
        this.length = 0;
        this.status = status;
        return this;
    }

    /** Clears the holder. */
    public void clear() {
        fail(-1);
    }
}
