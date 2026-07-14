// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.validation;

import io.ringloom.framework.annotation.RingloomTopicHandler;
import io.ringloom.framework.processor.ProcessorContext;
import io.ringloom.framework.processor.model.Symbols;
import io.ringloom.framework.processor.model.TopicHandler;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * Validates a {@link RingloomTopicHandler}-annotated method and builds the {@link TopicHandler} model.
 *
 * <p>The method must return {@code int}, take exactly two parameters {@code (Payload, TopicContext)},
 * declare a non-blank serializer, and — when {@code partitionKey} is set — the named accessor must exist
 * on the payload type.
 */
public final class TopicHandlerValidator {
    private final ProcessorContext ctx;

    public TopicHandlerValidator(ProcessorContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates the handler method.
     *
     * @param component  the enclosing component type
     * @param method     the handler method element
     * @param annotation the {@link RingloomTopicHandler} annotation
     * @return the handler model (always returned; errors are accumulated on {@code ctx})
     */
    public TopicHandler validate(TypeElement component, ExecutableElement method, RingloomTopicHandler annotation) {
        if (!method.getReturnType().toString().equals("int")) {
            ctx.error(method, "@RingloomTopicHandler method must return int");
        }
        List<? extends VariableElement> parameters = method.getParameters();
        if (parameters.size() != 2) {
            ctx.error(method, "@RingloomTopicHandler method must declare (Payload, TopicContext) parameters");
        }
        VariableElement payloadParameter = null;
        String payloadType = null;
        if (parameters.size() >= 1) {
            VariableElement first = parameters.get(0);
            String firstType = first.asType().toString();
            if (firstType.equals(Symbols.TOPIC_CONTEXT)
                    || firstType.equals(Symbols.MEMORY_SEGMENT)
                    || firstType.equals(Symbols.RINGLOOM_MESSAGE)
                    || firstType.equals(Symbols.MESSAGE_CONTEXT)) {
                ctx.error(method, "@RingloomTopicHandler first parameter must be the payload type");
            } else {
                payloadParameter = first;
                payloadType = firstType;
            }
        }
        if (parameters.size() >= 2) {
            String secondType = parameters.get(1).asType().toString();
            if (!secondType.equals(Symbols.TOPIC_CONTEXT)) {
                ctx.error(method, "@RingloomTopicHandler second parameter must be TopicContext");
            }
        }
        if (annotation.serializer().isBlank()) {
            ctx.error(method, "@RingloomTopicHandler serializer must be non-blank");
        }
        String partitionKey = annotation.partitionKey();
        if (partitionKey == null || partitionKey.isBlank()) {
            partitionKey = null;
        }
        return new TopicHandler(component, method, annotation, payloadParameter, payloadType, partitionKey);
    }
}
