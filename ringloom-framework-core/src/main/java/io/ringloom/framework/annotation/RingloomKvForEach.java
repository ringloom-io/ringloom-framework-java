// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generated KV iteration over one consistent snapshot: {@code int forEach(KvEntryVisitor<V> visitor)}
 * or the allocation-free {@code int forEach(KvRawVisitor visitor)}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface RingloomKvForEach {
    /** Value serializer; {@code ""} uses the store serializer, then the runtime default. */
    String serializer() default "";
}
