// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor;

import io.ringloom.framework.annotation.RequestMode;
import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.annotation.RingloomServiceComponent;
import io.ringloom.framework.annotation.RoutingMode;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
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

    private static final String MEMORY_SEGMENT = "java.lang.foreign.MemorySegment";
    private static final String RINGLOOM_MESSAGE = "io.ringloom.service.RingloomMessage";
    private static final String MESSAGE_CONTEXT = "io.ringloom.framework.dispatch.MessageContext";
    private static final String REQUEST_TIMEOUT = "io.ringloom.framework.request.RequestTimeout";
    private static final String RINGLOOM_REQUEST_EXCEPTION = "io.ringloom.framework.request.RingloomRequestException";
    private static final String INTERRUPTED_EXCEPTION = "java.lang.InterruptedException";
    private static final String SBE_SERIALIZER = "sbe";
    private static final String FORY_SERIALIZER = "fory";

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
        List<TypeElement> components = componentTypes(roundEnv);
        List<TypeElement> applications = types(roundEnv.getElementsAnnotatedWith(RingloomApplication.class));
        clients.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        components.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        applications.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));

        for (TypeElement client : clients) {
            validateClient(client, elements);
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

    private void validateClient(TypeElement client, Elements elements) {
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
            if (request.mode() == RequestMode.VIRTUAL_THREAD_BLOCKING) {
                validateVirtualThreadBlockingClientMethod(method, request, elements);
                continue;
            }
            if (request.mode() == RequestMode.CALLBACK) {
                error(method, "callback request/response client methods are not implemented yet");
                continue;
            }
            if (!returnsInt(method)) {
                error(method, "one-way generated client methods must return int status");
                continue;
            }
            if (request.routing() == RoutingMode.DIRECT) {
                if (!isDirectReplyMethod(method)) {
                    error(method, "direct generated client shape is int method(payload, MessageContext)");
                    continue;
                }
            } else if (!isSingleParameter(method)) {
                error(method, "one-way generated client shape is int method(payload)");
                continue;
            }
            if (!isMemorySegmentPayload(clientPayloadParameter(method))
                    && usesSbeSerializer(
                            request.serializer(),
                            clientPayloadParameter(method).asType().toString())) {
                validateSbeClientPayload(clientPayloadParameter(method), elements);
            }
        }
    }

    private void validateVirtualThreadBlockingClientMethod(
            ExecutableElement method, RingloomRequest request, Elements elements) {
        if (request.responseTemplateId() == -1) {
            error(method, "virtual-thread blocking requests require responseTemplateId");
            return;
        }
        if (method.getReturnType().toString().equals("void") || returnsInt(method)) {
            error(method, "virtual-thread blocking requests must return the decoded response type");
            return;
        }
        if (method.getParameters().size() != 2
                || !method.getParameters().get(1).asType().toString().equals(REQUEST_TIMEOUT)) {
            error(method, "virtual-thread blocking shape is Response method(payload, RequestTimeout)");
            return;
        }
        if (!throwsType(method, RINGLOOM_REQUEST_EXCEPTION) || !throwsType(method, INTERRUPTED_EXCEPTION)) {
            error(
                    method,
                    "virtual-thread blocking requests must declare RingloomRequestException and InterruptedException");
            return;
        }
        VariableElement payload = method.getParameters().getFirst();
        if (!isMemorySegmentPayload(payload)
                && usesSbeSerializer(request.serializer(), payload.asType().toString())) {
            validateSbeClientPayload(payload, elements);
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
                                    "packageName",
                                    pkg,
                                    "generatedName",
                                    generatedName,
                                    "simpleName",
                                    simpleName,
                                    "methodSources",
                                    methods.toString())));
        } catch (IOException ex) {
            error(client, "failed to generate client: " + ex.getMessage());
        }
    }

    private String clientMethodSource(ExecutableElement method) throws IOException {
        RingloomRequest request = method.getAnnotation(RingloomRequest.class);
        VariableElement payload = clientPayloadParameter(method);
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
                "serializer",
                escape(request.serializer()));
        if (request.mode() == RequestMode.VIRTUAL_THREAD_BLOCKING) {
            if (payloadType.equals(MEMORY_SEGMENT)) {
                return templates.render("client-memory-blocking-method.java.mustache", model);
            }
            return templates.render("client-serializer-blocking-method.java.mustache", model);
        }
        if (request.routing() == RoutingMode.DIRECT) {
            if (payloadType.equals(MEMORY_SEGMENT)) {
                return templates.render("client-memory-direct-method.java.mustache", model);
            }
            return templates.render("client-serializer-direct-method.java.mustache", model);
        }
        if (payloadType.equals(MEMORY_SEGMENT)) {
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
                validateHandler(method, elements);
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
        generateApplicationClass(pkg, appName, dispatcherName, service, clients, handlers, application, elements);
        generateProvider(pkg, providerName, appName, application);
        generateServiceFile(pkg, providerName);
    }

    private void generateDispatcher(String pkg, String dispatcherName, List<Handler> handlers, TypeElement origin) {
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
            writeSourceFile(
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
                        "templateId",
                        handler.templateId(),
                        "returnsInt",
                        returnsInt(method),
                        "fieldName",
                        fieldName,
                        "methodName",
                        method.getSimpleName().toString(),
                        "arguments",
                        handlerArguments(method, null)));
    }

    private String serializerDispatcherCaseSource(String fieldName, Handler handler, VariableElement payloadParameter)
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
                        returnsInt(method),
                        "fieldName",
                        fieldName,
                        "methodName",
                        method.getSimpleName().toString(),
                        "payloadType",
                        payloadType,
                        "serializer",
                        escape(annotation.serializer()),
                        "arguments",
                        handlerArguments(method, payloadParameter)));
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
                if (type.equals(RINGLOOM_MESSAGE)) {
                    arguments.append("message");
                } else if (type.equals(MESSAGE_CONTEXT)) {
                    arguments.append("context");
                } else if (type.equals(MEMORY_SEGMENT)) {
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
            List<Handler> handlers,
            TypeElement origin,
            Elements elements) {
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
                                    "packageName",
                                    pkg,
                                    "appName",
                                    appName,
                                    "dispatcherName",
                                    dispatcherName,
                                    "serviceName",
                                    escape(service),
                                    "clientBindingSources",
                                    clientBindings.toString(),
                                    "componentTypeSources",
                                    componentTypeSources(handlers),
                                    "serializerRegistrationSources",
                                    serializerRegistrationSources(clients, handlers, elements))));
        } catch (IOException ex) {
            error(origin, "failed to generate application: " + ex.getMessage());
        }
    }

    private String serializerRegistrationSources(List<TypeElement> clients, List<Handler> handlers, Elements elements) {
        Map<String, String> registrations = new LinkedHashMap<>();
        Set<String> explicitForyTypes = new TreeSet<>();
        Set<String> defaultForyTypes = new TreeSet<>();
        for (TypeElement client : clients) {
            for (Element enclosed : client.getEnclosedElements()) {
                if (!(enclosed instanceof ExecutableElement method)) {
                    continue;
                }
                RingloomRequest request = method.getAnnotation(RingloomRequest.class);
                if (request == null) {
                    continue;
                }
                if (isMemorySegmentOnly(method)) {
                    continue;
                }
                VariableElement payloadParameter = clientPayloadParameter(method);
                String payloadType = payloadParameter.asType().toString();
                collectForyClientTypes(
                        method, request, payloadParameter, explicitForyTypes, defaultForyTypes, elements);
                if (!usesSbeSerializer(request.serializer(), payloadType)) {
                    continue;
                }
                registrations.putIfAbsent(
                        "client|sbe|" + request.templateId() + "|" + payloadType,
                        sbeClientRegistrationSource(SBE_SERIALIZER, request.templateId(), payloadType, elements));
            }
        }
        for (Handler handler : handlers) {
            VariableElement payloadParameter = serializerPayloadParameter(handler.method());
            if (payloadParameter == null) {
                continue;
            }
            RingloomHandler annotation = handler.method().getAnnotation(RingloomHandler.class);
            if (annotation == null) {
                continue;
            }
            String payloadType = payloadParameter.asType().toString();
            collectForyTypes(
                    annotation.serializer(), payloadParameter.asType(), explicitForyTypes, defaultForyTypes, elements);
            if (!usesSbeSerializer(annotation.serializer(), payloadType)) {
                continue;
            }
            registrations.putIfAbsent(
                    "handler|sbe|" + handler.templateId() + "|" + payloadType,
                    sbeHandlerRegistrationSource(SBE_SERIALIZER, handler.templateId(), payloadType, elements));
        }
        StringBuilder out = new StringBuilder();
        for (String source : registrations.values()) {
            out.append(source);
        }
        out.append(foryRegistrationSource(explicitForyTypes, defaultForyTypes));
        return out.toString();
    }

    private void collectForyClientTypes(
            ExecutableElement method,
            RingloomRequest request,
            VariableElement payloadParameter,
            Set<String> explicitForyTypes,
            Set<String> defaultForyTypes,
            Elements elements) {
        collectForyTypes(
                request.serializer(), payloadParameter.asType(), explicitForyTypes, defaultForyTypes, elements);
        if (request.mode() == RequestMode.VIRTUAL_THREAD_BLOCKING) {
            collectForyTypes(
                    request.serializer(), method.getReturnType(), explicitForyTypes, defaultForyTypes, elements);
        }
    }

    private void collectForyTypes(
            String serializer,
            TypeMirror type,
            Set<String> explicitForyTypes,
            Set<String> defaultForyTypes,
            Elements elements) {
        if (serializer.equals(FORY_SERIALIZER)) {
            collectForyTypeGraph(type, explicitForyTypes, elements);
            return;
        }
        if (serializer.isBlank() && !usesSbeSerializer(serializer, type.toString())) {
            collectForyTypeGraph(type, defaultForyTypes, elements);
        }
    }

    private void collectForyTypeGraph(TypeMirror type, Set<String> result, Elements elements) {
        TypeKind kind = type.getKind();
        if (kind.isPrimitive()
                || kind == TypeKind.VOID
                || kind == TypeKind.NULL
                || kind == TypeKind.NONE
                || kind == TypeKind.WILDCARD) {
            return;
        }
        if (type instanceof ArrayType arrayType) {
            collectForyTypeGraph(arrayType.getComponentType(), result, elements);
            return;
        }
        if (!(type instanceof DeclaredType declaredType)
                || !(declaredType.asElement() instanceof TypeElement typeElement)) {
            return;
        }
        String typeName = typeElement.getQualifiedName().toString();
        for (TypeMirror argument : declaredType.getTypeArguments()) {
            collectForyTypeGraph(argument, result, elements);
        }
        if (typeName.startsWith("java.")) {
            return;
        }
        result.add(typeName);
        if (typeElement.getKind() == ElementKind.RECORD) {
            for (RecordComponentElement component : typeElement.getRecordComponents()) {
                collectForyTypeGraph(component.asType(), result, elements);
            }
        }
    }

    private String foryRegistrationSource(Set<String> explicitForyTypes, Set<String> defaultForyTypes) {
        Set<String> allTypes = new LinkedHashSet<>();
        allTypes.addAll(explicitForyTypes);
        allTypes.addAll(defaultForyTypes);
        if (allTypes.isEmpty()) {
            return "";
        }
        StringBuilder source = new StringBuilder();
        source.append("    java.util.LinkedHashSet<Class<?>> foryTypes = new java.util.LinkedHashSet<>();\n");
        for (String type : explicitForyTypes) {
            source.append("    foryTypes.add(").append(type).append(".class);\n");
        }
        if (!defaultForyTypes.isEmpty()) {
            source.append("    if (\"fory\".equals(serializers.defaultSerializer())) {\n");
            for (String type : defaultForyTypes) {
                source.append("      foryTypes.add(").append(type).append(".class);\n");
            }
            source.append("    }\n");
        }
        source.append("""
                    if (!foryTypes.isEmpty()) {
                      new io.ringloom.framework.serializer.fory.ForySerializerModule()
                          .register(
                              builder,
                              io.ringloom.framework.serializer.fory.ForySerializerConfig.from(serializers.entry("fory")),
                              java.util.List.copyOf(foryTypes));
                    }
            """);
        return source.toString();
    }

    private String sbeClientRegistrationSource(
            String serializer, int templateId, String payloadType, Elements elements) {
        SbePayloadModel payload = requireSbeDtoPayload(payloadType, null, elements);
        return """
            builder.encoder(
                \"%s\",
                %s.class,
                io.ringloom.framework.serializer.sbe.SbeCodecFactory.encoder(
                    %d,
                    (value, context) -> value.computeEncodedLength(),
                    (value, target, context) -> {
                      %s encoder = context.codec(%s.class, %s::new);
                      encoder.wrap(target, 0);
                      %s.encodeWith(encoder, value);
                      return encoder.encodedLength();
                    }));
        """.formatted(
                        escape(serializer),
                        payload.dtoType(),
                        templateId,
                        payload.encoderType(),
                        payload.encoderType(),
                        payload.encoderType(),
                        payload.dtoType());
    }

    private String sbeHandlerRegistrationSource(
            String serializer, int templateId, String payloadType, Elements elements) {
        SbePayloadModel payload = requireSbePayload(payloadType, null, elements);
        if (payload.decoderPayload()) {
            return """
                builder.flyweight(
                    \"%s\",
                    %s.class,
                    io.ringloom.framework.serializer.sbe.SbeCodecFactory.flyweight(
                        %d,
                        %s.class,
                        %s::new,
                        (decoder, source, context) -> decoder.wrap(
                            source,
                            0,
                            %s.BLOCK_LENGTH,
                            %s.SCHEMA_VERSION)));
            """.formatted(
                            escape(serializer),
                            payload.decoderType(),
                            templateId,
                            payload.decoderType(),
                            payload.decoderType(),
                            payload.decoderType(),
                            payload.decoderType());
        }
        return """
            builder.decoder(
                \"%s\",
                %s.class,
                new io.ringloom.framework.serializer.sbe.SbeDtoMessageDecoder<>(
                    %d,
                    %s.class,
                    %s::new,
                    %s.class,
                    %s::new,
                    (decoder, source, context) -> decoder.wrap(
                        source,
                        0,
                        %s.BLOCK_LENGTH,
                        %s.SCHEMA_VERSION),
                    %s::decodeWith));
        """.formatted(
                        escape(serializer),
                        payload.dtoType(),
                        templateId,
                        payload.decoderType(),
                        payload.decoderType(),
                        payload.dtoType(),
                        payload.dtoType(),
                        payload.decoderType(),
                        payload.decoderType(),
                        payload.dtoType());
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

    private void validateHandler(ExecutableElement method, Elements elements) {
        if (!returnsInt(method) && !method.getReturnType().toString().equals("void")) {
            error(method, "RingLoom handler must return int status or void");
        }
        RingloomHandler handler = method.getAnnotation(RingloomHandler.class);
        int serializerPayloads = 0;
        for (VariableElement parameter : method.getParameters()) {
            String type = parameter.asType().toString();
            if (!type.equals(RINGLOOM_MESSAGE) && !type.equals(MESSAGE_CONTEXT) && !type.equals(MEMORY_SEGMENT)) {
                serializerPayloads++;
                if (handler != null && usesSbeSerializer(handler.serializer(), type)) {
                    validateSbeHandlerPayload(parameter, elements);
                }
            }
        }
        if (serializerPayloads > 1) {
            error(method, "RingLoom handler may only declare one serializer-backed payload parameter");
        }
    }

    private void validateSbeClientPayload(VariableElement parameter, Elements elements) {
        requireSbeDtoPayload(parameter.asType().toString(), parameter, elements);
    }

    private void validateSbeHandlerPayload(VariableElement parameter, Elements elements) {
        requireSbePayload(parameter.asType().toString(), parameter, elements);
    }

    private SbePayloadModel requireSbeDtoPayload(String payloadType, Element origin, Elements elements) {
        SbePayloadModel payload = requireSbePayload(payloadType, origin, elements);
        if (!payload.dtoPayload()) {
            error(origin, "SBE client payload type must be a generated *Dto type: " + payloadType);
        }
        requireTypeExists(origin, elements, payload.encoderType(), "missing generated SBE encoder type ");
        return payload;
    }

    private SbePayloadModel requireSbePayload(String payloadType, Element origin, Elements elements) {
        SbePayloadModel payload = sbePayloadModel(payloadType);
        if (payload == null) {
            error(origin, "unsupported SBE payload type; expected generated *Dto or *Decoder type: " + payloadType);
            return new SbePayloadModel(payloadType, payloadType + "Encoder", payloadType + "Decoder", false, true);
        }
        requireTypeExists(origin, elements, payload.decoderType(), "missing generated SBE decoder type ");
        if (payload.dtoPayload()) {
            requireTypeExists(origin, elements, payload.dtoType(), "missing generated SBE DTO type ");
        }
        return payload;
    }

    private void requireTypeExists(Element origin, Elements elements, String typeName, String prefix) {
        if (elements.getTypeElement(typeName) == null) {
            error(origin, prefix + typeName);
        }
    }

    private boolean usesSbeSerializer(String serializer, String payloadType) {
        return (SBE_SERIALIZER.equals(serializer) || (serializer.isBlank() && sbePayloadModel(payloadType) != null));
    }

    private SbePayloadModel sbePayloadModel(String payloadType) {
        if (payloadType.endsWith("Dto")) {
            String baseType = payloadType.substring(0, payloadType.length() - 3);
            return new SbePayloadModel(payloadType, baseType + "Encoder", baseType + "Decoder", true, false);
        }
        if (payloadType.endsWith("Decoder")) {
            String baseType = payloadType.substring(0, payloadType.length() - 7);
            return new SbePayloadModel(baseType + "Dto", baseType + "Encoder", payloadType, false, true);
        }
        return null;
    }

    private boolean isMemorySegmentOnly(ExecutableElement method) {
        return (method.getParameters().size() == 1
                && method.getParameters().getFirst().asType().toString().equals(MEMORY_SEGMENT));
    }

    private boolean isMemorySegmentPayload(VariableElement parameter) {
        return parameter.asType().toString().equals(MEMORY_SEGMENT);
    }

    private boolean isSingleParameter(ExecutableElement method) {
        return method.getParameters().size() == 1;
    }

    private boolean isDirectReplyMethod(ExecutableElement method) {
        return method.getParameters().size() == 2
                && method.getParameters().get(1).asType().toString().equals(MESSAGE_CONTEXT);
    }

    private VariableElement clientPayloadParameter(ExecutableElement method) {
        return method.getParameters().getFirst();
    }

    private boolean returnsInt(ExecutableElement method) {
        return method.getReturnType().toString().equals("int");
    }

    private boolean throwsType(ExecutableElement method, String typeName) {
        for (var thrownType : method.getThrownTypes()) {
            if (thrownType.toString().equals(typeName)) {
                return true;
            }
        }
        return false;
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
        return (type.equals(RINGLOOM_MESSAGE) || type.equals(MESSAGE_CONTEXT) || type.equals(MEMORY_SEGMENT));
    }

    private void validateTemplate(Element element, int templateId) {
        if (templateId < 0 || templateId > 65_535) {
            error(element, "template id must be in unsigned 16-bit range: " + templateId);
        }
    }

    private List<TypeElement> componentTypes(RoundEnvironment roundEnv) {
        Map<String, TypeElement> result = new LinkedHashMap<>();
        for (TypeElement component : types(roundEnv.getElementsAnnotatedWith(RingloomServiceComponent.class))) {
            result.put(component.getQualifiedName().toString(), component);
        }
        for (Element handler : roundEnv.getElementsAnnotatedWith(RingloomHandler.class)) {
            Element enclosing = handler.getEnclosingElement();
            if (enclosing instanceof TypeElement component) {
                result.putIfAbsent(component.getQualifiedName().toString(), component);
            }
        }
        return new ArrayList<>(result.values());
    }

    private String componentTypeSources(List<Handler> handlers) {
        Map<String, String> componentTypes = new LinkedHashMap<>();
        for (Handler handler : handlers) {
            String type = handler.component().getQualifiedName().toString();
            componentTypes.putIfAbsent(type, "        " + type + ".class");
        }
        StringBuilder sources = new StringBuilder();
        int index = 0;
        for (String source : componentTypes.values()) {
            sources.append(source);
            if (++index < componentTypes.size()) {
                sources.append(",");
            }
            sources.append("\n");
        }
        return sources.toString();
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

    private record SbePayloadModel(
            String dtoType, String encoderType, String decoderType, boolean dtoPayload, boolean decoderPayload) {}
}
