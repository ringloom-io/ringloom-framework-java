// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

import io.ringloom.framework.status.RingloomHandlerStatus;

/**
 * Exception raised when a serializer cannot encode or decode a RingLoom payload.
 */
public final class RingloomSerializationException extends RuntimeException {
    private final int status;

    public RingloomSerializationException(String message) {
        this(message, RingloomHandlerStatus.SERIALIZATION_ERROR, null);
    }

    public RingloomSerializationException(String message, Throwable cause) {
        this(message, RingloomHandlerStatus.SERIALIZATION_ERROR, cause);
    }

    public RingloomSerializationException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /**
     * Returns the framework status associated with this serialization failure.
     *
     * @return the RingLoom framework status
     */
    public int status() {
        return status;
    }
}
