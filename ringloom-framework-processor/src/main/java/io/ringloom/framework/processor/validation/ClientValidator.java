// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.validation;

import io.ringloom.framework.annotation.RequestMode;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.annotation.RoutingMode;
import io.ringloom.framework.processor.ProcessorContext;
import io.ringloom.framework.processor.model.SbePayloadModel;
import io.ringloom.framework.processor.model.SourceHelpers;
import io.ringloom.framework.processor.model.Symbols;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;

public final class ClientValidator {

    private final ProcessorContext ctx;

    public ClientValidator(ProcessorContext ctx) {
        this.ctx = ctx;
    }

    public void validate(TypeElement client) {
        Elements elements = ctx.elementUtils();
        if (client.getKind() != ElementKind.INTERFACE) {
            ctx.error(client, "@RingloomClient may only annotate interfaces");
            return;
        }
        for (Element enclosed : client.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            Set<Modifier> modifiers = method.getModifiers();
            if (modifiers.contains(Modifier.DEFAULT)
                    || modifiers.contains(Modifier.STATIC)
                    || modifiers.contains(Modifier.PRIVATE)) {
                ctx.error(method, "RingLoom client methods must be abstract instance methods");
                continue;
            }
            RingloomRequest request = method.getAnnotation(RingloomRequest.class);
            if (request == null) {
                ctx.error(method, "RingLoom client methods require @RingloomRequest");
                continue;
            }
            TemplateRules.validate(method, request.templateId(), ctx);
            if (request.responseTemplateId() != -1) {
                TemplateRules.validate(method, request.responseTemplateId(), ctx);
            }
            if (request.mode() == RequestMode.VIRTUAL_THREAD_BLOCKING) {
                validateVirtualThreadBlocking(method, request, elements);
                continue;
            }
            if (request.mode() == RequestMode.CALLBACK) {
                ctx.error(method, "callback request/response client methods are not implemented yet");
                continue;
            }
            if (!SourceHelpers.returnsInt(method)) {
                ctx.error(method, "one-way generated client methods must return int status");
                continue;
            }
            if (request.routing() == RoutingMode.DIRECT) {
                if (!isDirectReplyMethod(method)) {
                    ctx.error(method, "direct generated client shape is int method(payload, MessageContext)");
                    continue;
                }
            } else if (!isSingleParameter(method)) {
                ctx.error(method, "one-way generated client shape is int method(payload)");
                continue;
            }
            if (!isMemorySegmentPayload(clientPayloadParameter(method))
                    && SbePayloadModel.usesSbe(
                            request.serializer(),
                            clientPayloadParameter(method).asType().toString())) {
                SbeValidator.validateClientPayload(clientPayloadParameter(method), elements, ctx);
            }
        }
    }

    public void validateVirtualThreadBlocking(ExecutableElement method, RingloomRequest request, Elements elements) {
        if (request.responseTemplateId() == -1) {
            ctx.error(method, "virtual-thread blocking requests require responseTemplateId");
            return;
        }
        if (method.getReturnType().toString().equals("void") || SourceHelpers.returnsInt(method)) {
            ctx.error(method, "virtual-thread blocking requests must return the decoded response type");
            return;
        }
        if (method.getParameters().size() != 2
                || !method.getParameters().get(1).asType().toString().equals(Symbols.REQUEST_TIMEOUT)) {
            ctx.error(method, "virtual-thread blocking shape is Response method(payload, RequestTimeout)");
            return;
        }
        if (!throwsType(method, Symbols.RINGLOOM_REQUEST_EXCEPTION)
                || !throwsType(method, Symbols.INTERRUPTED_EXCEPTION)) {
            ctx.error(
                    method,
                    "virtual-thread blocking requests must declare RingloomRequestException and InterruptedException");
            return;
        }
        VariableElement payload = method.getParameters().getFirst();
        if (!isMemorySegmentPayload(payload)
                && SbePayloadModel.usesSbe(
                        request.serializer(), payload.asType().toString())) {
            SbeValidator.validateClientPayload(payload, elements, ctx);
            SbeValidator.requireSbeDtoPayload(method.getReturnType().toString(), method, elements, ctx);
        }
    }

    public static boolean isMemorySegmentOnly(ExecutableElement method) {
        return (method.getParameters().size() == 1
                && method.getParameters().getFirst().asType().toString().equals(Symbols.MEMORY_SEGMENT));
    }

    public static boolean isMemorySegmentPayload(VariableElement parameter) {
        return parameter.asType().toString().equals(Symbols.MEMORY_SEGMENT);
    }

    public static boolean isSingleParameter(ExecutableElement method) {
        return method.getParameters().size() == 1;
    }

    public static boolean isDirectReplyMethod(ExecutableElement method) {
        return (method.getParameters().size() == 2
                && method.getParameters().get(1).asType().toString().equals(Symbols.MESSAGE_CONTEXT));
    }

    public static VariableElement clientPayloadParameter(ExecutableElement method) {
        return method.getParameters().getFirst();
    }

    public static boolean throwsType(ExecutableElement method, String typeName) {
        for (var thrownType : method.getThrownTypes()) {
            if (thrownType.toString().equals(typeName)) {
                return true;
            }
        }
        return false;
    }
}
