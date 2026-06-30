// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.generation;

import io.ringloom.framework.processor.ProcessorContext;
import io.ringloom.framework.processor.TemplateRenderer;
import java.util.Map;
import javax.lang.model.element.TypeElement;

public final class ProviderSourceGenerator {

    private final ProcessorContext ctx;
    private final TemplateRenderer templates;

    public ProviderSourceGenerator(ProcessorContext ctx, TemplateRenderer templates) {
        this.ctx = ctx;
        this.templates = templates;
    }

    public void generate(String pkg, String providerName, String appName, TypeElement origin) {
        String qualifiedName = pkg.isEmpty() ? providerName : pkg + "." + providerName;
        try {
            new SourceWriter(ctx)
                    .writeSourceFile(
                            qualifiedName,
                            origin,
                            templates.render(
                                    "provider.java.mustache",
                                    Map.of("packageName", pkg, "providerName", providerName, "appName", appName)));
        } catch (java.io.IOException ex) {
            ctx.error(origin, "failed to generate provider: " + ex.getMessage());
        }
    }
}
