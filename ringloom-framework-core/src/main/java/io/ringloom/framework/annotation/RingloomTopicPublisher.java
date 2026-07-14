// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a generated persistent-topic publisher. Each method on the interface should
 * carry {@link RingloomTopicPublish}.
 *
 * @see RingloomTopicPublish
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface RingloomTopicPublisher {
    /** Topic name this publisher writes to (UTF-8, 1..16 bytes). */
    String topic();

    /** Ringloom-queue roll scheme name; defaults to {@code "FAST_DAILY"}. */
    String rollScheme() default "FAST_DAILY";

    /** Retained ring cycles; {@code 0} keeps all. */
    int retentionCycles() default 0;

    /** Optional client alias used to resolve the low-level client; empty selects the default. */
    String client() default "";
}
