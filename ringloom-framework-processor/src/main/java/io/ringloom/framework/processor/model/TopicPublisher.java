// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.model;

import io.ringloom.framework.annotation.RingloomTopicPublisher;
import java.util.List;
import javax.lang.model.element.TypeElement;

/**
 * Model for a {@link io.ringloom.framework.annotation.RingloomTopicPublisher}-annotated interface.
 *
 * @param publisherInterface the annotated interface element
 * @param annotation         the annotation mirror (topic/rollScheme/retentionCycles/client)
 * @param publishMethods     one {@link TopicPublish} per {@code @RingloomTopicPublish} method
 */
public record TopicPublisher(
        TypeElement publisherInterface, RingloomTopicPublisher annotation, List<TopicPublish> publishMethods) {}
