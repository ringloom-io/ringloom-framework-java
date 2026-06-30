// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.validation;

import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.processor.ProcessorContext;
import io.ringloom.framework.processor.model.SbePayloadModel;
import io.ringloom.framework.processor.model.SourceHelpers;
import io.ringloom.framework.processor.model.Symbols;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;

public final class HandlerValidator {

    private final ProcessorContext ctx;

    public HandlerValidator(ProcessorContext ctx) {
        this.ctx = ctx;
    }

    public void validate(ExecutableElement method) {
        Elements elements = ctx.elementUtils();
        if (!SourceHelpers.returnsInt(method)
                && !method.getReturnType().toString().equals("void")) {
            ctx.error(method, "RingLoom handler must return int status or void");
        }
        RingloomHandler handler = method.getAnnotation(RingloomHandler.class);
        int serializerPayloads = 0;
        for (VariableElement parameter : method.getParameters()) {
            String type = parameter.asType().toString();
            if (!type.equals(Symbols.RINGLOOM_MESSAGE)
                    && !type.equals(Symbols.MESSAGE_CONTEXT)
                    && !type.equals(Symbols.MEMORY_SEGMENT)) {
                serializerPayloads++;
                if (handler != null && SbePayloadModel.usesSbe(handler.serializer(), type)) {
                    SbeValidator.validateHandlerPayload(parameter, elements, ctx);
                }
            }
        }
        if (serializerPayloads > 1) {
            ctx.error(method, "RingLoom handler may only declare one serializer-backed payload parameter");
        }
    }

    public static VariableElement serializerPayloadParameter(ExecutableElement method) {
        for (VariableElement parameter : method.getParameters()) {
            if (!isSpecialHandlerParameter(parameter)) {
                return parameter;
            }
        }
        return null;
    }

    public static boolean isSpecialHandlerParameter(VariableElement parameter) {
        String type = parameter.asType().toString();
        return (type.equals(Symbols.RINGLOOM_MESSAGE)
                || type.equals(Symbols.MESSAGE_CONTEXT)
                || type.equals(Symbols.MEMORY_SEGMENT));
    }
}
