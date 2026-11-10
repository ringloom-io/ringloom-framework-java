// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.validation;

import io.ringloom.framework.annotation.RingloomKvWriterStateHandler;
import io.ringloom.framework.processor.ProcessorContext;
import io.ringloom.framework.processor.model.KvStore;
import io.ringloom.framework.processor.model.KvWriterStateHandler;
import io.ringloom.framework.processor.model.Symbols;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;

/**
 * Validates {@link RingloomKvWriterStateHandler} methods (§9.3 rule 7): {@code public void
 * m(KvWriterStateEvent event)} on a service component, for a store that some interface writes.
 */
public final class KvWriterStateHandlerValidator {
    private final ProcessorContext ctx;

    public KvWriterStateHandlerValidator(ProcessorContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates one handler method.
     *
     * @param component  the declaring component
     * @param method     the annotated method
     * @param annotation the annotation
     * @param stores     every store interface of the compilation
     * @return the model, or {@code null} when invalid
     */
    public KvWriterStateHandler validate(
            TypeElement component,
            ExecutableElement method,
            RingloomKvWriterStateHandler annotation,
            List<KvStore> stores) {
        boolean valid = true;
        if (!method.getModifiers().contains(Modifier.PUBLIC)
                || method.getModifiers().contains(Modifier.STATIC)) {
            ctx.error(method, "@RingloomKvWriterStateHandler methods must be public instance methods");
            valid = false;
        }
        if (method.getReturnType().getKind() != TypeKind.VOID
                || method.getParameters().size() != 1
                || !method.getParameters().getFirst().asType().toString().equals(Symbols.KV_WRITER_STATE_EVENT)) {
            ctx.error(
                    method, "@RingloomKvWriterStateHandler signature must be: public void m(KvWriterStateEvent event)");
            valid = false;
        }
        String store = annotation.store();
        boolean written = stores.stream().anyMatch(s -> s.name().equals(store) && s.writes());
        if (!written) {
            ctx.error(
                    method,
                    "@RingloomKvWriterStateHandler store '" + store
                            + "' is not written by any @RingloomKvStore interface");
            valid = false;
        }
        return valid ? new KvWriterStateHandler(component, method, store) : null;
    }
}
