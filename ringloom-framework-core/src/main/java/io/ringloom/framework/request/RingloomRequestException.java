// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.request;

/**
 * Exception raised by blocking request APIs when a request completes with a non-success status.
 */
public final class RingloomRequestException extends Exception {
    private final int status;

    public RingloomRequestException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
