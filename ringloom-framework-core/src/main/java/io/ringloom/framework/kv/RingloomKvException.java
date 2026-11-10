// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

/** Startup or configuration failure of a KV store (for example the writer lease is held by another client). */
public final class RingloomKvException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Native status code, or {@code -1} when the failure did not come from native code. */
    private final int statusCode;

    /**
     * Creates an exception.
     *
     * @param message    the message
     * @param statusCode the native status code, or {@code -1}
     * @param cause      the cause, may be {@code null}
     */
    public RingloomKvException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * Returns the native status code, or {@code -1} when not applicable.
     *
     * @return the status code
     */
    public int statusCode() {
        return statusCode;
    }
}
