// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import io.ringloom.service.TopicStart;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method on a {@link RingloomServiceComponent} as a persistent-topic handler. The method
 * receives topic messages polled from the named topic.
 *
 * <p>The method signature is {@code int handle(Payload payload, TopicContext context)} where the
 * payload is decoded by the named {@link #serializer()}. {@link #partitionKey()} optionally names a
 * field accessor used for partition-key extraction in partitioned-worker mode.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface RingloomTopicHandler {
    /** Topic name this handler subscribes to (UTF-8, 1..16 bytes). */
    String topic();

    /** Starting position for the subscription; defaults to {@link TopicStart#EARLIEST}. */
    TopicStart start() default TopicStart.EARLIEST;

    /** Serializer name used to decode the handler payload. */
    String serializer();

    /** Partition-key extractor name (an accessor on the payload), or empty for keyless routing. */
    String partitionKey() default "";
}
