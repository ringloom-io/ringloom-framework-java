// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.validation;

import io.ringloom.framework.annotation.RingloomSchedule;
import io.ringloom.framework.processor.ProcessorContext;
import java.util.Set;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;

public final class ScheduleValidator {

    private final ProcessorContext ctx;

    public ScheduleValidator(ProcessorContext ctx) {
        this.ctx = ctx;
    }

    public void validate(ExecutableElement method, RingloomSchedule schedule) {
        if (!method.getReturnType().toString().equals("void")) {
            ctx.error(method, "RingLoom scheduled method must return void");
        }
        if (!method.getParameters().isEmpty()) {
            ctx.error(method, "RingLoom scheduled method must not declare parameters");
        }
        Set<Modifier> modifiers = method.getModifiers();
        if (modifiers.contains(Modifier.STATIC) || !modifiers.contains(Modifier.PUBLIC)) {
            ctx.error(method, "RingLoom scheduled method must be a public instance method");
        }
        if (schedule.initialDelayMillis() < 0) {
            ctx.error(method, "RingLoom schedule initialDelayMillis must be non-negative");
        }
        boolean fixedRate = schedule.fixedRateMillis() > 0;
        boolean fixedDelay = schedule.fixedDelayMillis() > 0;
        if (fixedRate == fixedDelay) {
            ctx.error(method, "RingLoom schedule requires exactly one positive fixedRateMillis or fixedDelayMillis");
        }
    }
}
