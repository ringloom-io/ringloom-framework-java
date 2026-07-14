// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.model;

import io.ringloom.framework.annotation.RingloomTopicPublish;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;

/**
 * Model for a {@link RingloomTopicPublish}-annotated publish method.
 *
 * @param method          the publish method element
 * @param annotation      the annotation (serializer/ackMode/errorPolicy)
 * @param payloadParameter the payload parameter (first non-special parameter)
 * @param payloadType     the payload type's fully-qualified name
 * @param ackCallback     whether the method declares an {@code AckCallback} parameter
 */
public record TopicPublish(
        ExecutableElement method,
        RingloomTopicPublish annotation,
        VariableElement payloadParameter,
        String payloadType,
        boolean ackCallback) {}
