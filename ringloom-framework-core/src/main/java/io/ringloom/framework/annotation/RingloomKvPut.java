// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import io.ringloom.service.TopicAckMode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generated KV put: {@code int put(K key, V value)} or {@code int put(K key, MemorySegment value)},
 * with an optional {@link RingloomKvTtl} {@code long} parameter, and with
 * {@code (AckCallback callback, Object context)} as the last two parameters when {@link #ackMode()}
 * is {@code REPLICATE_ONCE}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface RingloomKvPut {
    /** Value serializer; {@code ""} uses the store serializer, then the runtime default. */
    String serializer() default "";

    /** Ack mode; {@code REPLICATE_ONCE} requires the ack parameters. */
    TopicAckMode ackMode() default TopicAckMode.FIRE_AND_FORGET;

    /** TTL in milliseconds: {@code -1} store default, {@code 0} never; overridden by a {@link RingloomKvTtl} parameter. */
    long ttlMillis() default -1;
}
