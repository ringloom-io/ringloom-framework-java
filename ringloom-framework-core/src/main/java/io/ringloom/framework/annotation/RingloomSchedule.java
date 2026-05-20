// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service component method for compile-time scheduler registration.
 *
 * <p>Scheduled methods must be public, instance, no-argument methods returning {@code void}. The
 * processor registers them with {@code RingloomRuntime.scheduler()} when the runtime starts, so
 * callbacks execute on the runtime control thread.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface RingloomSchedule {
    /**
     * Delay before the first invocation, in milliseconds.
     *
     * @return initial delay in milliseconds
     */
    long initialDelayMillis() default 0;

    /**
     * Fixed-rate period, in milliseconds. Exactly one of {@link #fixedRateMillis()} and {@link
     * #fixedDelayMillis()} must be positive.
     *
     * @return fixed-rate period in milliseconds
     */
    long fixedRateMillis() default -1;

    /**
     * Fixed-delay interval after each callback completes, in milliseconds. Exactly one of {@link
     * #fixedRateMillis()} and {@link #fixedDelayMillis()} must be positive.
     *
     * @return fixed-delay interval in milliseconds
     */
    long fixedDelayMillis() default -1;
}
