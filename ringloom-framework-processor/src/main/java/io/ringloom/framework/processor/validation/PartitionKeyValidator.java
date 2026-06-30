// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.validation;

import io.ringloom.framework.annotation.RingloomPartitionKey;
import io.ringloom.framework.processor.ProcessorContext;
import io.ringloom.framework.processor.model.PartitionKey;
import io.ringloom.framework.processor.model.Symbols;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

public final class PartitionKeyValidator {

    private final ProcessorContext ctx;

    public PartitionKeyValidator(ProcessorContext ctx) {
        this.ctx = ctx;
    }

    public PartitionKey resolve(ExecutableElement method) {
        RingloomPartitionKey methodKey = method.getAnnotation(RingloomPartitionKey.class);
        VariableElement annotatedParameter = null;
        RingloomPartitionKey parameterKey = null;
        for (VariableElement parameter : method.getParameters()) {
            RingloomPartitionKey key = parameter.getAnnotation(RingloomPartitionKey.class);
            if (key == null) {
                continue;
            }
            if (parameterKey != null || methodKey != null) {
                ctx.error(parameter, "RingLoom handler may declare only one @RingloomPartitionKey");
                return null;
            }
            annotatedParameter = parameter;
            parameterKey = key;
        }
        if (methodKey == null && parameterKey == null) {
            return null;
        }
        VariableElement parameter =
                annotatedParameter == null ? HandlerValidator.serializerPayloadParameter(method) : annotatedParameter;
        if (parameter == null) {
            ctx.error(method, "@RingloomPartitionKey requires a payload, RingloomMessage, or MessageContext parameter");
            return null;
        }
        String type = parameter.asType().toString();
        if (type.equals(Symbols.MEMORY_SEGMENT)) {
            ctx.error(parameter, "MemorySegment parameters cannot be used as generated partition keys");
            return null;
        }
        if (type.equals(Symbols.RINGLOOM_MESSAGE) || type.equals(Symbols.MESSAGE_CONTEXT)) {
            return new PartitionKey(parameter, "");
        }
        RingloomPartitionKey annotation = parameterKey == null ? methodKey : parameterKey;
        String accessor = annotation.value().isBlank() ? "partitionKey" : annotation.value();
        if (!hasNoArgMethod(parameter.asType(), accessor)) {
            ctx.error(parameter, "partition key payload type must declare no-argument accessor " + accessor + "()");
        }
        return new PartitionKey(parameter, accessor);
    }

    static boolean hasNoArgMethod(TypeMirror type, String methodName) {
        if (!(type instanceof DeclaredType declaredType)
                || !(declaredType.asElement() instanceof TypeElement typeElement)) {
            return false;
        }
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement method
                    && method.getSimpleName().contentEquals(methodName)
                    && method.getParameters().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
