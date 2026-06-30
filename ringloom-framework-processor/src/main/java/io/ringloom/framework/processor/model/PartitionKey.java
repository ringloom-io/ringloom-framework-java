// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.model;

import javax.lang.model.element.VariableElement;

public record PartitionKey(VariableElement parameter, String accessor) {}
