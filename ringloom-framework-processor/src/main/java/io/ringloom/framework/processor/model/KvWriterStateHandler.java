// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.model;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Model for a {@code @RingloomKvWriterStateHandler} method.
 *
 * @param component the declaring service component
 * @param method    the handler method
 * @param store     the store name
 */
public record KvWriterStateHandler(TypeElement component, ExecutableElement method, String store) {}
