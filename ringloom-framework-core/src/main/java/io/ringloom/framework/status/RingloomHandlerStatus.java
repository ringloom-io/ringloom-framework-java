// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.status;

import io.ringloom.service.RingloomStatus;

public final class RingloomHandlerStatus {
    public static final int OK = RingloomStatus.OK;
    public static final int UNKNOWN_TEMPLATE_ID = 65_536;
    public static final int SERIALIZATION_ERROR = 65_537;
    public static final int REQUEST_TIMEOUT = 65_538;
    public static final int REQUEST_CANCELLED = 65_539;
    public static final int SHUTDOWN = 65_540;
    public static final int NATIVE_SYMBOL_UNAVAILABLE = 65_541;

    private RingloomHandlerStatus() {
    }
}
