// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor;

import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.annotation.RingloomServiceComponent;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

@SupportedSourceVersion(SourceVersion.RELEASE_25)
/**
 * Annotation processor that generates RingLoom clients, dispatchers, and application metadata.
 */
public final class RingloomFrameworkProcessor extends AbstractProcessor {
    private final TemplateRenderer templates = new TemplateRenderer();
    private boolean generated;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(
                RingloomApplication.class.getCanonicalName(),
                RingloomClient.class.getCanonicalName(),
                RingloomRequest.class.getCanonicalName(),
                RingloomServiceComponent.class.getCanonicalName(),
                RingloomHandler.class.getCanonicalName());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || generated) {
            return false;
        }
        Elements elements = processingEnv.getElementUtils();
        List<TypeElement> clients = types(roundEnv.getElementsAnnotatedWith(RingloomClient.class));
        List<TypeElement> components = types(roundEnv.getElementsAnnotatedWith(RingloomServiceComponent.class));
        List<TypeElement> applications = types(roundEnv.getElementsAnnotatedWith(RingloomApplication.class));
        clients.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        components.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        applications.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));

        for (TypeElement client : clients) {
            validateClient(client);
            generateClient(client, elements);
        }
        if (!components.isEmpty() || !applications.isEmpty()) {
            TypeElement application = applications.isEmpty() ? components.getFirst() : applications.getFirst();
            if (applications.size() > 1) {
                error(
                        applications.get(1),
                        "multiple @RingloomApplication types require an explicit single application");
                return true;
            }
            generateApplication(application, clients, components, elements);
        }
        generated = true;
        return false;
    }

    private void validateClient(TypeElement client) {
        if (client.getKind() != ElementKind.INTERFACE) {
            error(client, "@RingloomClient may only annotate interfaces");
            return;
        }
        for (Element enclosed : client.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            Set<Modifier> modifiers = method.getModifiers();
            if (modifiers.contains(Modifier.DEFAULT)
                    || modifiers.contains(Modifier.STATIC)
                    || modifiers.contains(Modifier.PRIVATE)) {
                error(method, "RingLoom client methods must be abstract instance methods");
                continue;
            }
            RingloomRequest request = method.getAnnotation(RingloomRequest.class);
            if (request == null) {
                error(method, "RingLoom client methods require @RingloomRequest");
                continue;
            }
            validateTemplate(method, request.templateId());
            if (request.responseTemplateId() != -1) {
                validateTemplate(method, request.responseTemplateId());
            }
            if (!returnsInt(method)) {
                error(method, "currently supported generated client shape must return int status");
                continue;
            }
            if (!isSingleParameter(method)) {
                error(method, "currently supported generated client shape is int method(payload)");
                continue;
            }
            if (!isMemorySegmentOnly(method) && request.serializer().isBlank()) {
                error(method, "serializer-backed client methods must declare @RingloomRequest.serializer");
            }
        }
    }

    private void generateClient(TypeElement client, Elements elements) {
        String pkg = packageName(elements, client);
        String simpleName = client.getSimpleName().toString();
        String generatedName = simpleName + "_RingloomClient";
        String qualifiedName = pkg.isEmpty() ? generatedName : pkg + "." + generatedName;
        try {
            StringBuilder methods = new StringBuilder();
            for (Element enclosed : client.getEnclosedElements()) {
                if (enclosed.getKind() == ElementKind.METHOD) {
                    methods.append(clientMethodSource((ExecutableElement) enclosed));
                }
            }
            writeSourceFile(
                    qualifiedName,
                    client,
                    templates.render(
                            "client.java.mustache",
                            Map.of(
                                    "packageName", pkg,
                                    "generatedName", generatedName,
                                    "simpleName", simpleName,
                                    "methodSources", methods.toString())));
        } catch (IOException ex) {
            error(client, "failed to generate client: " + ex.getMessage());
        }
    }

    private String clientMethodSource(ExecutableElement method) throws IOException {
        RingloomRequest request = method.getAnnotation(RingloomRequest.class);
        String payloadType = method.getParameters().getFirst().asType().toString();
        Map<String, Object> model = Map.of(
                "methodName", method.getSimpleName().toString(),
                "payloadType", payloadType,
                "payloadName", method.getParameters().getFirst().getSimpleName().toString(),
                "templateId", request.templateId(),
                "serializer", escape(request.serializer()));
        if (payloadType.equals("java.lang.foreign.MemorySegment")) {
            return templates.render("client-memory-method.java.mustache", model);
        }
        return templates.render("client-serializer-method.java.mustache", model);
    }

    private void generateApplication(
            TypeElement application, List<TypeElement> clients, List<TypeElement> components, Elements elements) {
        Map<Integer, ExecutableElement> handlerTemplateIds = new HashMap<>();
        List<Handler> handlers = new ArrayList<>();
        for (TypeElement component : components) {
            for (Element enclosed : component.getEnclosedElements()) {
                RingloomHandler annotation = enclosed.getAnnotation(RingloomHandler.class);
                if (annotation == null) {
                    continue;
                }
                if (!(enclosed instanceof ExecutableElement method)) {
                    continue;
                }
                validateTemplate(method, annotation.templateId());
                ExecutableElement previous = handlerTemplateIds.putIfAbsent(annotation.templateId(), method);
                if (previous != null) {
                    error(method, "duplicate RingLoom handler template id " + annotation.templateId());
                }
                validateHandler(method);
                handlers.add(new Handler(component, method, annotation.templateId()));
            }
        }
        handlers.sort(Comparator.comparingInt(Handler::templateId));

        String pkg = packageName(elements, application);
        String appSimple = application.getSimpleName().toString();
        String service = serviceName(application, appSimple);
        String dispatcherName = appSimple + "_RingloomDispatcher";
        String appName = appSimple + "_RingloomApplication";
        String providerName = appSimple + "_RingloomApplicationProvider";
        generateDispatcher(pkg, dispatcherName, handlers, application);
        generateApplicationClass(pkg, appName, dispatcherName, service, clients, application);
        generateProvider(pkg, providerName, appName, application);
        generateServiceFile(pkg, providerName);
    }

    private void generateDispatcher(String pkg, String dispatcherName, List<Handler> handlers, TypeElement origin) {
        String qualifiedName = pkg.isEmpty() ? dispatcherName : pkg + "." + dispatcherName;
        try {
            List<Map<String, Object>> fields = new ArrayList<>();
            StringBuilder cases = new StringBuilder();
            for (int i = 0; i < handlers.size(); i++) {
                Handler handler = handlers.get(i);
                String fieldName = "h" + i;
                fields.add(Map.of(
                        "componentType", handler.component().getQualifiedName().toString(), "fieldName", fieldName));
                cases.append(dispatcherCaseSource(fieldName, handler));
            }
            writeSourceFile(
                    qualifiedName,
                    origin,
                    templates.render(
                            "dispatcher.java.mustache",
                            Map.of(
                                    "packageName", pkg,
                                    "dispatcherName", dispatcherName,
                                    "handlerFields", fields,
                                    "caseSources", cases.toString())));
        } catch (IOException ex) {
            error(origin, "failed to generate dispatcher: " + ex.getMessage());
        }
    }

    private String dispatcherCaseSource(String fieldName, Handler handler) throws IOException {
        ExecutableElement method = handler.method();
        VariableElement payloadParameter = serializerPayloadParameter(method);
        if (payloadParameter != null) {
            return serializerDispatcherCaseSource(fieldName, handler, payloadParameter);
        }
        return templates.render(
                "dispatcher-direct-case.java.mustache",
                Map.of(
                        "templateId", handler.templateId(),
                        "returnsInt", returnsInt(method),
                        "fieldName", fieldName,
                        "methodName", method.getSimpleName().toString(),
                        "arguments", handlerArguments(method, null)));
    }

    private String serializerDispatcherCaseSource(String fieldName, Handler handler, VariableElement payloadParameter)
            throws IOException {
        ExecutableElement method = handler.method();
        RingloomHandler annotation = method.getAnnotation(RingloomHandler.class);
        String payloadType = payloadParameter.asType().toString();
        return templates.render(
                "dispatcher-serializer-case.java.mustache",
                Map.of(
                        "templateId", handler.templateId(),
                        "returnsInt", returnsInt(method),
                        "fieldName", fieldName,
                        "methodName", method.getSimpleName().toString(),
                        "payloadType", payloadType,
                        "serializer", escape(annotation.serializer()),
                        "arguments", handlerArguments(method, payloadParameter)));
    }

    private String handlerArguments(ExecutableElement method, VariableElement serializerPayloadParameter) {
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
                if (type.equals("io.ringloom.service.RingloomMessage")) {
                    arguments.append("message");
                } else if (type.equals("io.ringloom.framework.dispatch.MessageContext")) {
                    arguments.append("context");
                } else if (type.equals("java.lang.foreign.MemorySegment")) {
                    arguments.append("context.payloadSegment()");
                }
            }
        }
        return arguments.toString();
    }

    private void generateApplicationClass(
            String pkg,
            String appName,
            String dispatcherName,
            String service,
            List<TypeElement> clients,
            TypeElement origin) {
        String qualifiedName = pkg.isEmpty() ? appName : pkg + "." + appName;
        try {
            StringBuilder clientBindings = new StringBuilder();
            for (int i = 0; i < clients.size(); i++) {
                TypeElement client = clients.get(i);
                String clientName = client.getQualifiedName().toString();
                clientBindings.append(templates.render(
                        "application-client-binding.java.mustache",
                        Map.of(
                                "clientName",
                                clientName,
                                "generatedClientName",
                                clientName + "_RingloomClient",
                                "targetServiceName",
                                escape(client.getAnnotation(RingloomClient.class)
                                        .service()),
                                "comma",
                                i + 1 == clients.size() ? "" : ",")));
            }
            writeSourceFile(
                    qualifiedName,
                    origin,
                    templates.render(
                            "application.java.mustache",
                            Map.of(
                                    "packageName", pkg,
                                    "appName", appName,
                                    "dispatcherName", dispatcherName,
                                    "serviceName", escape(service),
                                    "clientBindingSources", clientBindings.toString())));
        } catch (IOException ex) {
            error(origin, "failed to generate application: " + ex.getMessage());
        }
    }

    private void generateProvider(String pkg, String providerName, String appName, TypeElement origin) {
        String qualifiedName = pkg.isEmpty() ? providerName : pkg + "." + providerName;
        try {
            writeSourceFile(
                    qualifiedName,
                    origin,
                    templates.render(
                            "provider.java.mustache",
                            Map.of("packageName", pkg, "providerName", providerName, "appName", appName)));
        } catch (IOException ex) {
            error(origin, "failed to generate provider: " + ex.getMessage());
        }
    }

    private void writeSourceFile(String qualifiedName, Element origin, String source) throws IOException {
        JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, origin);
        try (Writer writer = file.openWriter()) {
            writer.write(source);
        }
    }

    private void generateServiceFile(String pkg, String providerName) {
        try {
            Filer filer = processingEnv.getFiler();
            FileObject file = filer.createResource(
                    StandardLocation.CLASS_OUTPUT,
                    "",
                    "META-INF/services/io.ringloom.framework.generated.GeneratedRingloomApplicationProvider");
            try (Writer writer = file.openWriter()) {
                writer.write((pkg.isEmpty() ? providerName : pkg + "." + providerName) + "\n");
            }
        } catch (IOException ex) {
            processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.ERROR, "failed to generate service file: " + ex.getMessage());
        }
    }

    private void validateHandler(ExecutableElement method) {
        if (!returnsInt(method) && !method.getReturnType().toString().equals("void")) {
            error(method, "RingLoom handler must return int status or void");
        }
        RingloomHandler handler = method.getAnnotation(RingloomHandler.class);
        int serializerPayloads = 0;
        for (VariableElement parameter : method.getParameters()) {
            String type = parameter.asType().toString();
            if (!type.equals("io.ringloom.service.RingloomMessage")
                    && !type.equals("io.ringloom.framework.dispatch.MessageContext")
                    && !type.equals("java.lang.foreign.MemorySegment")) {
                serializerPayloads++;
                if (handler == null || handler.serializer().isBlank()) {
                    error(
                            method,
                            "serializer-backed handler parameter " + type + " requires @RingloomHandler.serializer");
                }
            }
        }
        if (serializerPayloads > 1) {
            error(method, "RingLoom handler may only declare one serializer-backed payload parameter");
        }
    }

    private boolean isMemorySegmentOnly(ExecutableElement method) {
        return method.getParameters().size() == 1
                && method.getParameters().getFirst().asType().toString().equals("java.lang.foreign.MemorySegment");
    }

    private boolean isSingleParameter(ExecutableElement method) {
        return method.getParameters().size() == 1;
    }

    private boolean returnsInt(ExecutableElement method) {
        return method.getReturnType().toString().equals("int");
    }

    private VariableElement serializerPayloadParameter(ExecutableElement method) {
        for (VariableElement parameter : method.getParameters()) {
            if (!isSpecialHandlerParameter(parameter)) {
                return parameter;
            }
        }
        return null;
    }

    private boolean isSpecialHandlerParameter(VariableElement parameter) {
        String type = parameter.asType().toString();
        return type.equals("io.ringloom.service.RingloomMessage")
                || type.equals("io.ringloom.framework.dispatch.MessageContext")
                || type.equals("java.lang.foreign.MemorySegment");
    }

    private void validateTemplate(Element element, int templateId) {
        if (templateId < 0 || templateId > 65_535) {
            error(element, "template id must be in unsigned 16-bit range: " + templateId);
        }
    }

    private static List<TypeElement> types(Set<? extends Element> elements) {
        List<TypeElement> result = new ArrayList<>();
        for (Element element : elements) {
            if (element instanceof TypeElement typeElement) {
                result.add(typeElement);
            }
        }
        return result;
    }

    private static String packageName(Elements elements, TypeElement type) {
        PackageElement pkg = elements.getPackageOf(type);
        return pkg.isUnnamed() ? "" : pkg.getQualifiedName().toString();
    }

    private static String serviceName(TypeElement application, String fallback) {
        RingloomApplication annotation = application.getAnnotation(RingloomApplication.class);
        if (annotation == null || annotation.service().isBlank()) {
            return fallback;
        }
        return annotation.service();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private record Handler(TypeElement component, ExecutableElement method, int templateId) {}
}
