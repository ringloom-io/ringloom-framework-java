// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.SOURCE)
/**
 * Marks a handler method or parameter as contributing the partition key for partitioned message
 * execution.
 */
public @interface RingloomPartitionKey {
    /**
     * Names the no-argument accessor used on a serializer-backed payload to read the key. When
     * blank, generated code calls {@code partitionKey()}.
     *
     * @return the payload accessor method used to compute the partition key
     */
    String value() default "";
}
