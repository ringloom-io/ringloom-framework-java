// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.generation;

import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.processor.ProcessorContext;
import io.ringloom.framework.processor.TemplateRenderer;
import io.ringloom.framework.processor.model.Handler;
import io.ringloom.framework.processor.model.SourceHelpers;
import io.ringloom.framework.processor.model.Symbols;
import io.ringloom.framework.processor.validation.HandlerValidator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

public final class DispatcherSourceGenerator {

    private final ProcessorContext ctx;
    private final TemplateRenderer templates;

    public DispatcherSourceGenerator(ProcessorContext ctx, TemplateRenderer templates) {
        this.ctx = ctx;
        this.templates = templates;
    }

    public void generate(String pkg, String dispatcherName, List<Handler> handlers, TypeElement origin) {
        String qualifiedName = pkg.isEmpty() ? dispatcherName : pkg + "." + dispatcherName;
        try {
            List<Map<String, Object>> fields = new ArrayList<>();
            Map<String, String> fieldNames = new LinkedHashMap<>();
            StringBuilder cases = new StringBuilder();
            for (Handler handler : handlers) {
                String componentType = handler.component().getQualifiedName().toString();
                String fieldName = fieldNames.get(componentType);
                if (fieldName == null) {
                    fieldName = "h" + fieldNames.size();
                    fieldNames.put(componentType, fieldName);
                    fields.add(Map.of("componentType", componentType, "fieldName", fieldName));
                }
                cases.append(dispatcherCaseSource(fieldName, handler));
            }
            new SourceWriter(ctx)
                    .writeSourceFile(
                            qualifiedName,
                            origin,
                            templates.render(
                                    "dispatcher.java.mustache",
                                    Map.of(
                                            "packageName",
                                            pkg,
                                            "dispatcherName",
                                            dispatcherName,
                                            "handlerFields",
                                            fields,
                                            "caseSources",
                                            cases.toString())));
        } catch (IOException ex) {
            ctx.error(origin, "failed to generate dispatcher: " + ex.getMessage());
        }
    }

    public String dispatcherCaseSource(String fieldName, Handler handler) throws IOException {
        ExecutableElement method = handler.method();
        VariableElement payloadParameter = HandlerValidator.serializerPayloadParameter(method);
        if (payloadParameter != null) {
            return serializerDispatcherCaseSource(fieldName, handler, payloadParameter);
        }
        return templates.render(
                "dispatcher-direct-case.java.mustache",
                Map.of(
                        "templateId",
                        handler.templateId(),
                        "returnsInt",
                        SourceHelpers.returnsInt(method),
                        "fieldName",
                        fieldName,
                        "methodName",
                        method.getSimpleName().toString(),
                        "arguments",
                        handlerArguments(method, null)));
    }

    public String serializerDispatcherCaseSource(String fieldName, Handler handler, VariableElement payloadParameter)
            throws IOException {
        ExecutableElement method = handler.method();
        RingloomHandler annotation = method.getAnnotation(RingloomHandler.class);
        String payloadType = payloadParameter.asType().toString();
        return templates.render(
                "dispatcher-serializer-case.java.mustache",
                Map.of(
                        "templateId",
                        handler.templateId(),
                        "returnsInt",
                        SourceHelpers.returnsInt(method),
                        "fieldName",
                        fieldName,
                        "methodName",
                        method.getSimpleName().toString(),
                        "payloadType",
                        payloadType,
                        "serializer",
                        SourceHelpers.escape(annotation.serializer()),
                        "arguments",
                        handlerArguments(method, payloadParameter)));
    }

    public String handlerArguments(ExecutableElement method, VariableElement serializerPayloadParameter) {
        StringBuilder arguments = new StringBuilder();
        List<? extends VariableElement> parameters = method.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                arguments.append(", ");
            }
            VariableElement parameter = parameters.get(i);
            if (parameter == serializerPayloadParameter) {
                arguments.append("decoded");
            } else {
                String type = parameter.asType().toString();
                if (type.equals(Symbols.RINGLOOM_MESSAGE)) {
                    arguments.append("message");
                } else if (type.equals(Symbols.MESSAGE_CONTEXT)) {
                    arguments.append("context");
                } else if (type.equals(Symbols.MEMORY_SEGMENT)) {
                    arguments.append("context.payloadSegment()");
                }
            }
        }
        return arguments.toString();
    }
}
