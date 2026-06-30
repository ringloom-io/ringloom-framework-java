// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor;

import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

public final class ProcessorContext {

    private final ProcessingEnvironment env;

    public ProcessorContext(ProcessingEnvironment env) {
        this.env = env;
    }

    public ProcessingEnvironment env() {
        return env;
    }

    public Elements elementUtils() {
        return env.getElementUtils();
    }

    public Filer filer() {
        return env.getFiler();
    }

    public javax.annotation.processing.Messager messager() {
        return env.getMessager();
    }

    public void error(Element element, String message) {
        messager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
