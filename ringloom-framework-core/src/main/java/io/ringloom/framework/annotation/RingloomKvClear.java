// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import io.ringloom.service.TopicAckMode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generated KV clear: {@code int clear()}, plus {@code (AckCallback callback, Object context)} as the last
 * two parameters when {@link #ackMode()} is {@code REPLICATE_ONCE}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface RingloomKvClear {
    /** Ack mode; {@code REPLICATE_ONCE} requires the ack parameters. */
    TopicAckMode ackMode() default TopicAckMode.FIRE_AND_FORGET;
}
