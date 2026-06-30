// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.model;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

public record Handler(TypeElement component, ExecutableElement method, int templateId, PartitionKey partitionKey) {}
