// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.generation;

import io.ringloom.framework.processor.ProcessorContext;
import io.ringloom.framework.processor.TemplateRenderer;
import io.ringloom.framework.processor.model.SourceHelpers;
import io.ringloom.framework.processor.model.TopicHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.TypeElement;

/**
 * Generates the {@code <App>_RingloomTopicDispatcher} that routes topic messages to
 * {@link io.ringloom.framework.annotation.RingloomTopicHandler} methods by topic id.
 */
public final class TopicDispatcherSourceGenerator {
    private final ProcessorContext ctx;
    private final TemplateRenderer templates;

    public TopicDispatcherSourceGenerator(ProcessorContext ctx, TemplateRenderer templates) {
        this.ctx = ctx;
        this.templates = templates;
    }

    /**
     * Generates the topic dispatcher.
     *
     * @param pkg             the target package
     * @param dispatcherName  the generated dispatcher class name
     * @param handlers        the validated topic handlers
     * @param origin          the origin element for source-file attribution
     */
    public void generate(String pkg, String dispatcherName, List<TopicHandler> handlers, TypeElement origin) {
        String qualifiedName = pkg.isEmpty() ? dispatcherName : pkg + "." + dispatcherName;
        try {
            List<Map<String, Object>> fields = new ArrayList<>();
            Map<String, String> componentFieldNames = new LinkedHashMap<>();
            Map<String, String> topicFieldNames = new LinkedHashMap<>();
            StringBuilder cases = new StringBuilder();
            List<Map<String, Object>> topicIds = new ArrayList<>();
            List<String> initialTopicIds = new ArrayList<>();
            int topicIndex = 0;
            for (TopicHandler handler : handlers) {
                String componentType = handler.component().getQualifiedName().toString();
                String componentField = componentFieldNames.get(componentType);
                if (componentField == null) {
                    componentField = "h" + componentFieldNames.size();
                    componentFieldNames.put(componentType, componentField);
                    fields.add(Map.of("componentType", componentType, "fieldName", componentField));
                }
                String topicKey = handler.annotation().topic();
                String topicField = topicFieldNames.get(topicKey);
                if (topicField == null) {
                    topicField = sanitizeTopicField(topicKey);
                    topicFieldNames.put(topicKey, topicField);
                    topicIds.add(Map.of("fieldName", topicField, "index", topicIndex));
                    initialTopicIds.add("0L");
                    topicIndex++;
                }
                cases.append(dispatcherCaseSource(componentField, topicField, handler));
            }
            Map<String, Object> model = new HashMap<>();
            model.put("packageName", pkg);
            model.put("dispatcherName", dispatcherName);
            model.put("handlerFields", fields);
            model.put("topicIds", topicIds);
            model.put("initialTopicIds", String.join(", ", initialTopicIds));
            model.put("caseSources", cases.toString());
            new SourceWriter(ctx)
                    .writeSourceFile(qualifiedName, origin, templates.render("topic-dispatcher.java.mustache", model));
        } catch (IOException ex) {
            ctx.error(origin, "failed to generate topic dispatcher: " + ex.getMessage());
        }
    }

    private String dispatcherCaseSource(String componentField, String topicField, TopicHandler handler)
            throws IOException {
        Map<String, Object> model = new HashMap<>();
        model.put("fieldName", componentField);
        model.put("topicField", topicField);
        model.put("methodName", handler.method().getSimpleName().toString());
        model.put("serializer", SourceHelpers.escape(handler.annotation().serializer()));
        model.put("payloadType", handler.payloadType() == null ? "java.lang.Object" : handler.payloadType());
        return templates.render("topic-dispatcher-case.java.mustache", model);
    }

    private static String sanitizeTopicField(String topicName) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < topicName.length(); i++) {
            char c = topicName.charAt(i);
            if (Character.isJavaIdentifierPart(c)) {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        String result = out.toString();
        if (result.isEmpty() || Character.isDigit(result.charAt(0))) {
            result = "topic" + result;
        }
        return result;
    }
}
