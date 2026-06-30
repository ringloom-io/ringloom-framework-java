// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.model;

public final class Symbols {

    public static final String MEMORY_SEGMENT = "java.lang.foreign.MemorySegment";
    public static final String RINGLOOM_MESSAGE = "io.ringloom.service.RingloomMessage";
    public static final String MESSAGE_CONTEXT = "io.ringloom.framework.dispatch.MessageContext";
    public static final String REQUEST_TIMEOUT = "io.ringloom.framework.request.RequestTimeout";
    public static final String RINGLOOM_REQUEST_EXCEPTION = "io.ringloom.framework.request.RingloomRequestException";
    public static final String INTERRUPTED_EXCEPTION = "java.lang.InterruptedException";
    public static final String SBE_SERIALIZER = "sbe";
    public static final String FORY_SERIALIZER = "fory";

    private Symbols() {}
}
