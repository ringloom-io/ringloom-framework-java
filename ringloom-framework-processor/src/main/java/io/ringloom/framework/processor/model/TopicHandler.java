// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.model;

import io.ringloom.framework.annotation.RingloomTopicHandler;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * Model for a {@link RingloomTopicHandler}-annotated handler method.
 *
 * @param component        the enclosing component type
 * @param method           the handler method element
 * @param annotation       the annotation (topic/start/serializer/partitionKey)
 * @param payloadParameter the serializer-decoded payload parameter, or {@code null}
 * @param payloadType      the payload type name, or {@code null}
 * @param partitionKey     the partition-key extractor accessor name, or {@code null}/{@code ""} for keyless
 */
public record TopicHandler(
        TypeElement component,
        ExecutableElement method,
        RingloomTopicHandler annotation,
        VariableElement payloadParameter,
        String payloadType,
        String partitionKey) {}
