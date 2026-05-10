// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
/**
 * Marks a service component method as the handler for an incoming message template.
 */
public @interface RingloomHandler {
    /**
     * Declares the inbound template id that should dispatch to the annotated handler.
     *
     * @return the unsigned 16-bit template id
     */
    int templateId();

    /**
     * Selects the serializer name used to decode the handler payload.
     *
     * @return the configured serializer name, or an empty string to use defaults
     */
    String serializer() default "";

    /**
     * Names the partition-key extractor to use when the handler runs under partitioned execution.
     *
     * @return the logical partition-key extractor name, or an empty string when not needed
     */
    String partitionKey() default "";
}
