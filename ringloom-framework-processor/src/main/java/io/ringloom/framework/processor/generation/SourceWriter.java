// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.generation;

import io.ringloom.framework.processor.ProcessorContext;

public final class SourceWriter {

    private final ProcessorContext ctx;

    public SourceWriter(ProcessorContext ctx) {
        this.ctx = ctx;
    }

    public void writeSourceFile(String qualifiedName, javax.lang.model.element.Element origin, String source)
            throws java.io.IOException {
        javax.tools.JavaFileObject file = ctx.filer().createSourceFile(qualifiedName, origin);
        try (java.io.Writer writer = file.openWriter()) {
            writer.write(source);
        }
    }

    public void writeServiceFile(String pkg, String providerName) {
        try {
            javax.annotation.processing.Filer filer = ctx.filer();
            javax.tools.FileObject file = filer.createResource(
                    javax.tools.StandardLocation.CLASS_OUTPUT,
                    "",
                    "META-INF/services/io.ringloom.framework.generated.GeneratedRingloomApplicationProvider");
            try (java.io.Writer writer = file.openWriter()) {
                writer.write((pkg.isEmpty() ? providerName : pkg + "." + providerName) + "\n");
            }
        } catch (java.io.IOException ex) {
            ctx.error(null, "failed to generate service file: " + ex.getMessage());
        }
    }
}
