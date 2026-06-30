// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.validation;

import io.ringloom.framework.processor.ProcessorContext;
import javax.lang.model.element.Element;

public final class TemplateRules {

    private TemplateRules() {}

    public static void validate(Element element, int templateId, ProcessorContext ctx) {
        if (templateId < 0 || templateId > 65_535) {
            ctx.error(element, "template id must be in unsigned 16-bit range: " + templateId);
        }
    }
}
