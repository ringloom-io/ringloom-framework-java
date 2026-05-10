// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
/**
 * Marks a method as a generated response handler for a specific template id.
 */
public @interface RingloomResponseHandler {
    /**
     * Declares the inbound response template id handled by the annotated method.
     *
     * @return the unsigned 16-bit response template id
     */
    int templateId();

    /**
     * Selects the serializer name used to decode the response payload.
     *
     * @return the serializer name, or an empty string to use defaults
     */
    String serializer() default "";
}
