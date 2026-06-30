// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.generation;

import io.ringloom.framework.annotation.RequestMode;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.annotation.RoutingMode;
import io.ringloom.framework.processor.ProcessorContext;
import io.ringloom.framework.processor.TemplateRenderer;
import io.ringloom.framework.processor.model.SbePayloadModel;
import io.ringloom.framework.processor.model.SourceHelpers;
import io.ringloom.framework.processor.model.Symbols;
import io.ringloom.framework.processor.validation.ClientValidator;
import io.ringloom.framework.processor.validation.SbeValidator;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

public final class ClientSourceGenerator {

    private final ProcessorContext ctx;
    private final TemplateRenderer templates;

    public ClientSourceGenerator(ProcessorContext ctx, TemplateRenderer templates) {
        this.ctx = ctx;
        this.templates = templates;
    }

    public void generate(TypeElement client) {
        String pkg = SourceHelpers.packageName(ctx.elementUtils(), client);
        String simpleName = client.getSimpleName().toString();
        String generatedName = simpleName + "_RingloomClient";
        String qualifiedName = pkg.isEmpty() ? generatedName : pkg + "." + generatedName;
        try {
            StringBuilder methods = new StringBuilder();
            for (Element enclosed : client.getEnclosedElements()) {
                if (enclosed.getKind() == ElementKind.METHOD) {
                    methods.append(methodSource(client, (ExecutableElement) enclosed));
                }
            }
            new SourceWriter(ctx)
                    .writeSourceFile(
                            qualifiedName,
                            client,
                            templates.render(
                                    "client.java.mustache",
                                    Map.of(
                                            "packageName",
                                            pkg,
                                            "generatedName",
                                            generatedName,
                                            "simpleName",
                                            simpleName,
                                            "targetServiceName",
                                            SourceHelpers.escape(client.getAnnotation(RingloomClient.class)
                                                    .service()),
                                            "methodSources",
                                            methods.toString())));
        } catch (IOException ex) {
            ctx.error(client, "failed to generate client: " + ex.getMessage());
        }
    }

    public String methodSource(TypeElement client, ExecutableElement method) throws IOException {
        RingloomRequest request = method.getAnnotation(RingloomRequest.class);
        VariableElement payload = ClientValidator.clientPayloadParameter(method);
        String payloadType = payload.asType().toString();
        Map<String, Object> model = Map.of(
                "methodName",
                method.getSimpleName().toString(),
                "payloadType",
                payloadType,
                "payloadName",
                payload.getSimpleName().toString(),
                "templateId",
                request.templateId(),
                "responseTemplateId",
                request.responseTemplateId(),
                "responseType",
                method.getReturnType().toString(),
                "routingMode",
                request.routing().name(),
                "clientName",
                client.getSimpleName().toString(),
                "targetServiceName",
                SourceHelpers.escape(client.getAnnotation(RingloomClient.class).service()),
                "serializer",
                SourceHelpers.escape(request.serializer()));
        if (request.mode() == RequestMode.VIRTUAL_THREAD_BLOCKING) {
            if (payloadType.equals(Symbols.MEMORY_SEGMENT)) {
                return templates.render("client-memory-blocking-method.java.mustache", model);
            }
            if (SbePayloadModel.usesSbe(request.serializer(), payloadType)
                    && SbePayloadModel.from(method.getReturnType().toString()) != null) {
                SbePayloadModel response = SbeValidator.requireSbeDtoPayload(
                        method.getReturnType().toString(), method, ctx.elementUtils(), ctx);
                Map<String, Object> sbeModel = new HashMap<>(model);
                sbeModel.put("responseDecoderType", response.decoderType());
                return templates.render("client-sbe-blocking-method.java.mustache", sbeModel);
            }
            return templates.render("client-serializer-blocking-method.java.mustache", model);
        }
        if (request.routing() == RoutingMode.DIRECT) {
            if (payloadType.equals(Symbols.MEMORY_SEGMENT)) {
                return templates.render("client-memory-direct-method.java.mustache", model);
            }
            return templates.render("client-serializer-direct-method.java.mustache", model);
        }
        if (payloadType.equals(Symbols.MEMORY_SEGMENT)) {
            return templates.render("client-memory-method.java.mustache", model);
        }
        return templates.render("client-serializer-method.java.mustache", model);
    }
}
