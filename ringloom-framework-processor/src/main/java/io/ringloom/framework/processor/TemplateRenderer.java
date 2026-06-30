// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class TemplateRenderer {

    private static final String TEMPLATE_ROOT = "/io/ringloom/framework/processor/templates/";

    private final Map<String, Template> templates = new HashMap<>();

    public String render(String templateName, Object model) throws IOException {
        return template(templateName).execute(model);
    }

    private Template template(String templateName) throws IOException {
        Template cached = templates.get(templateName);
        if (cached != null) {
            return cached;
        }
        String resourceName = TEMPLATE_ROOT + templateName;
        try (InputStream input = TemplateRenderer.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("missing template " + resourceName);
            }
            Template compiled = Mustache.compiler()
                    .escapeHTML(false)
                    .compile(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            templates.put(templateName, compiled);
            return compiled;
        }
    }
}
