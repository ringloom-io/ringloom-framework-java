// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
/**
 * Marks a lifecycle callback method on a {@link RingloomServiceComponent}.
 */
public @interface RingloomLifecycleHandler {
    /**
     * Narrows the callback to a specific generated client alias when applicable.
     *
     * @return the client alias, or an empty string when the callback is not client-specific
     */
    String client() default "";
}
