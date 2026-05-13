// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
/**
 * Optionally marks a class as participating in generated RingLoom service wiring.
 *
 * <p>Classes that declare {@link RingloomHandler} methods are discovered automatically; this
 * annotation remains useful as an explicit marker for non-IoC applications and source readability.
 */
public @interface RingloomServiceComponent {}
