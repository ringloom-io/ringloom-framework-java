// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import io.ringloom.service.TopicAckMode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method on a {@link RingloomTopicPublisher} interface as a publish operation.
 *
 * <p>The method's first parameter is the payload; the named {@link #serializer()} encodes it. When
 * {@link #ackMode()} is {@link TopicAckMode#REPLICATE_ONCE} the method may declare an
 * {@link io.ringloom.framework.topic.ack.AckCallback} parameter to receive completion feedback.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface RingloomTopicPublish {
    /** Serializer name used to encode the payload. */
    String serializer();

    /** Acknowledgement mode for this publish. */
    TopicAckMode ackMode() default TopicAckMode.FIRE_AND_FORGET;

    /** Error policy when the publish status is non-OK. */
    ErrorPolicy errorPolicy() default ErrorPolicy.STATUS;
}
