// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.validation;

import io.ringloom.framework.annotation.RingloomTopicPublish;
import io.ringloom.framework.processor.ProcessorContext;
import io.ringloom.framework.processor.model.Symbols;
import io.ringloom.framework.processor.model.TopicPublish;
import io.ringloom.framework.processor.model.TopicPublisher;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * Validates a {@link io.ringloom.framework.annotation.RingloomTopicPublisher}-annotated interface and
 * its {@link RingloomTopicPublish} methods, building the {@link TopicPublisher} model.
 */
public final class TopicPublisherValidator {
    private final ProcessorContext ctx;

    public TopicPublisherValidator(ProcessorContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates the publisher interface and collects its publish methods.
     *
     * @param publisher the annotated interface element
     * @return the publisher model (always returned; errors are accumulated on {@code ctx})
     */
    public TopicPublisher validate(TypeElement publisher) {
        if (publisher.getKind() != ElementKind.INTERFACE) {
            ctx.error(publisher, "@RingloomTopicPublisher may only annotate interfaces");
        }
        io.ringloom.framework.annotation.RingloomTopicPublisher annotation =
                publisher.getAnnotation(io.ringloom.framework.annotation.RingloomTopicPublisher.class);
        List<TopicPublish> publishMethods = new ArrayList<>();
        for (Element enclosed : publisher.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            RingloomTopicPublish publish = method.getAnnotation(RingloomTopicPublish.class);
            if (publish == null) {
                continue;
            }
            publishMethods.add(validatePublishMethod(method, publish));
        }
        if (publishMethods.isEmpty()) {
            ctx.error(publisher, "@RingloomTopicPublisher interface declares no @RingloomTopicPublish methods");
        }
        return new TopicPublisher(publisher, annotation, List.copyOf(publishMethods));
    }

    private TopicPublish validatePublishMethod(ExecutableElement method, RingloomTopicPublish annotation) {
        List<? extends VariableElement> parameters = method.getParameters();
        if (parameters.isEmpty()) {
            ctx.error(method, "@RingloomTopicPublish method must declare a payload parameter");
            return new TopicPublish(method, annotation, null, "java.lang.Object", false);
        }
        VariableElement payloadParameter = parameters.get(0);
        String payloadType = payloadParameter.asType().toString();
        boolean ackCallback = false;
        if (payloadType.equals(Symbols.ACK_CALLBACK) || payloadType.equals(Symbols.MEMORY_SEGMENT)) {
            ctx.error(method, "@RingloomTopicPublish first parameter must be the payload type");
        }
        // Detect an AckCallback parameter (any position after the payload).
        for (int i = 1; i < parameters.size(); i++) {
            String paramType = parameters.get(i).asType().toString();
            if (paramType.equals(Symbols.ACK_CALLBACK)) {
                ackCallback = true;
            }
        }
        String ackMode = annotation.ackMode().name();
        if (!ackCallback && ackMode.equals("REPLICATE_ONCE")) {
            ctx.error(
                    method, "@RingloomTopicPublish with REPLICATE_ONCE ack mode must declare an AckCallback parameter");
        }
        return new TopicPublish(method, annotation, payloadParameter, payloadType, ackCallback);
    }
}
