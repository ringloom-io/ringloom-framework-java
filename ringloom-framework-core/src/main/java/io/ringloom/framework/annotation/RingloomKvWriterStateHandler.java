// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code public void m(KvWriterStateEvent event)} method of a {@link RingloomServiceComponent}
 * that receives writer lease transitions of a store written by a {@link RingloomKvStore} interface.
 * Handlers run on the control thread after native control polling returns; it is safe to call KV APIs
 * from them. Exceptions are logged and never escape into the control loop.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface RingloomKvWriterStateHandler {
    /** The store name. */
    String store();
}
