// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
/**
 * Marks the primary application type used to generate standalone RingLoom bootstrap metadata.
 */
public @interface RingloomApplication {
    /**
     * Overrides the logical RingLoom service name associated with the annotated application type.
     *
     * @return the generated service name override, or an empty string to use the type name
     */
    String service() default "";
}
