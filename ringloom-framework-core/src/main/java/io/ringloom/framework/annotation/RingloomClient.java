// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
/**
 * Declares a generated client interface for another RingLoom service.
 */
public @interface RingloomClient {
    /**
     * Identifies the target service that the generated client should connect to.
     *
     * @return the logical RingLoom service name
     */
    String service();
}
