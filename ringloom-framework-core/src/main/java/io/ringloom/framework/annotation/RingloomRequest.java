// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
/**
 * Declares the message template, serializer, routing, and response behavior for a generated client
 * method.
 */
public @interface RingloomRequest {
    /**
     * Declares the outbound template id used for the generated send or request.
     *
     * @return the unsigned 16-bit request template id
     */
    int templateId();

    /**
     * Declares the expected response template id for request/response interactions.
     *
     * @return the response template id, or {@code -1} for one-way sends
     */
    int responseTemplateId() default -1;

    /**
     * Selects the serializer name used by the generated client method.
     *
     * @return the serializer name, or an empty string to use runtime defaults
     */
    String serializer() default "";

    /**
     * Chooses how the runtime should select the destination service instance.
     *
     * @return the outbound routing mode
     */
    RoutingMode routing() default RoutingMode.LOAD_BALANCED;

    /**
     * Chooses the request profile exposed by the generated client method.
     *
     * @return the request mode
     */
    RequestMode mode() default RequestMode.ONE_WAY;

    /**
     * Controls how send failures should be surfaced to the caller.
     *
     * @return the generated client's error policy
     */
    ErrorPolicy errorPolicy() default ErrorPolicy.STATUS;
}
