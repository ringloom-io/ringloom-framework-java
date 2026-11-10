// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generated KV read. Accepted shapes: {@code V get(K key)} ({@code null} when missing, expired, not
 * ready, or undecodable), {@code int get(K key, KvValue<V> out)} (status profile), and
 * {@code int get(K key, MemorySegment destination, long[] outLength)} (raw copy).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface RingloomKvGet {
    /** Value serializer; {@code ""} uses the store serializer, then the runtime default. */
    String serializer() default "";
}
