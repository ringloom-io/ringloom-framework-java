// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.status;

import io.ringloom.service.RingloomStatus;

/**
 * Framework-defined handler status codes that extend the low-level native RingLoom status space.
 */
public final class RingloomHandlerStatus {
    /**
     * Successful completion.
     */
    public static final int OK = RingloomStatus.OK;
    /**
     * No generated handler exists for the received template id.
     */
    public static final int UNKNOWN_TEMPLATE_ID = 65_536;
    /**
     * Payload encoding or decoding failed.
     */
    public static final int SERIALIZATION_ERROR = 65_537;
    /**
     * A pending request timed out before a response arrived.
     */
    public static final int REQUEST_TIMEOUT = 65_538;
    /**
     * A pending request was cancelled before completion.
     */
    public static final int REQUEST_CANCELLED = 65_539;
    /**
     * The runtime is shutting down and cannot continue processing.
     */
    public static final int SHUTDOWN = 65_540;
    /**
     * A required native symbol or ABI hook is not available.
     */
    public static final int NATIVE_SYMBOL_UNAVAILABLE = 65_541;
    /**
     * A topic message referenced a topic id with no registered generated handler.
     */
    public static final int UNKNOWN_TOPIC = 65_542;

    private RingloomHandlerStatus() {}
}
