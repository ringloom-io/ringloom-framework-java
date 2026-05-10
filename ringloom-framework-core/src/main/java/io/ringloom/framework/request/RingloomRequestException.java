// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.request;

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
