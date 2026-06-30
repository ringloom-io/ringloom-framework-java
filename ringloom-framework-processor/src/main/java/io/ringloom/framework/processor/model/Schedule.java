// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.model;

import io.ringloom.framework.annotation.RingloomSchedule;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

public record Schedule(TypeElement component, ExecutableElement method, RingloomSchedule annotation) {}
