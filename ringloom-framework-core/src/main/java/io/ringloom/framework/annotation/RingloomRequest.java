// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface RingloomRequest {
    int templateId();

    int responseTemplateId() default -1;

    String serializer() default "";

    RoutingMode routing() default RoutingMode.LOAD_BALANCED;

    RequestMode mode() default RequestMode.ONE_WAY;

    ErrorPolicy errorPolicy() default ErrorPolicy.STATUS;
}
