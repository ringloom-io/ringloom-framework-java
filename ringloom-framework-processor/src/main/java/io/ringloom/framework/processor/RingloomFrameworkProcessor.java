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
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, client);
            try (Writer writer = file.openWriter()) {
                if (!pkg.isEmpty()) {
                    writer.write("package " + pkg + ";\n\n");
                }
                writer.write("import io.ringloom.framework.RingloomRuntime;\n");
                writer.write("import io.ringloom.framework.serialization.EncodeContext;\n");
                writer.write("import io.ringloom.framework.serialization.MessageEncoder;\n");
                writer.write("import io.ringloom.framework.serialization.SerializerRegistry;\n");
                writer.write("import io.ringloom.framework.status.RingloomHandlerStatus;\n");
                writer.write("import io.ringloom.service.BufferClaim;\n");
                writer.write("import io.ringloom.service.RingloomStatus;\n");
                writer.write("import java.lang.foreign.MemorySegment;\n");
                writer.write("import java.lang.foreign.ValueLayout;\n\n");
                writer.write("public final class " + generatedName + " implements " + simpleName + " {\n");
                writer.write("  private final io.ringloom.service.RingloomClient lowLevelClient;\n");
                writer.write("  private final SerializerRegistry serializers;\n");
                writer.write("  private final BufferClaim claim;\n");
                writer.write("  private final EncodeContext encodeContext = new EncodeContext();\n");
                writer.write(
                        "  public " + generatedName
                                + "(RingloomRuntime runtime, io.ringloom.service.RingloomClient lowLevelClient, SerializerRegistry serializers) {\n");
                writer.write(
                        "    this.lowLevelClient = java.util.Objects.requireNonNull(lowLevelClient, \"lowLevelClient\");\n");
                writer.write(
                        "    this.serializers = java.util.Objects.requireNonNull(serializers, \"serializers\");\n");
                writer.write("    this.claim = lowLevelClient.newClaim();\n");
                writer.write("  }\n");
                for (Element enclosed : client.getEnclosedElements()) {
                    if (enclosed.getKind() == ElementKind.METHOD) {
                        writeClientMethod(writer, (ExecutableElement) enclosed);
                    }
                }
                writer.write("}\n");
            }
        } catch (IOException ex) {
            error(client, "failed to generate client: " + ex.getMessage());
        }
    }

    private void writeClientMethod(Writer writer, ExecutableElement method) throws IOException {
        RingloomRequest request = method.getAnnotation(RingloomRequest.class);
        String methodName = method.getSimpleName().toString();
        String payloadType = method.getParameters().getFirst().asType().toString();
        String payloadName = method.getParameters().getFirst().getSimpleName().toString();
        writer.write("  @Override public int " + methodName + "(" + payloadType + " " + payloadName + ") {\n");
        if (payloadType.equals("java.lang.foreign.MemorySegment")) {
            writer.write("    MemorySegment segment = " + payloadName + " == null ? MemorySegment.NULL : " + payloadName
                    + ";\n");
            writer.write("    int status = lowLevelClient.tryClaim(" + request.templateId()
                    + ", segment.byteSize(), claim);\n");
            writer.write("    if (status != RingloomStatus.OK) return status;\n");
            writer.write(
                    "    MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, 0, claim.payloadSegment(), ValueLayout.JAVA_BYTE, 0, segment.byteSize());\n");
            writer.write("    return claim.commit();\n");
        } else {
            writer.write("    MessageEncoder<" + payloadType + "> encoder = serializers.encoder(\""
                    + escape(request.serializer()) + "\", " + payloadType + ".class);\n");
            writer.write("    if (encoder == null) return RingloomHandlerStatus.SERIALIZATION_ERROR;\n");
            writer.write("    final int payloadLength;\n");
            writer.write("    try {\n");
            writer.write("      payloadLength = encoder.encodedLength(" + payloadName + ", encodeContext);\n");
            writer.write("    } catch (RuntimeException ex) {\n");
            writer.write("      return RingloomHandlerStatus.SERIALIZATION_ERROR;\n");
            writer.write("    }\n");
            writer.write(
                    "    int status = lowLevelClient.tryClaim(" + request.templateId() + ", payloadLength, claim);\n");
            writer.write("    if (status != RingloomStatus.OK) return status;\n");
            writer.write("    try {\n");
            writer.write("      encoder.encode(" + payloadName
                    + ", encodeContext.buffer().wrap(claim.payloadSegment()), encodeContext);\n");
            writer.write("      return claim.commit();\n");
            writer.write("    } catch (RuntimeException ex) {\n");
            writer.write("      claim.abort();\n");
            writer.write("      return RingloomHandlerStatus.SERIALIZATION_ERROR;\n");
            writer.write("    }\n");
        }
        writer.write("  }\n");
    }

    private void generateApplication(
            TypeElement application, List<TypeElement> clients, List<TypeElement> components, Elements elements) {
        Map<Integer, ExecutableElement> templates = new HashMap<>();
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
                ExecutableElement previous = templates.putIfAbsent(annotation.templateId(), method);
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
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, origin);
            try (Writer writer = file.openWriter()) {
                if (!pkg.isEmpty()) {
                    writer.write("package " + pkg + ";\n\n");
                }
                writer.write("import io.ringloom.framework.dispatch.MessageContext;\n");
                writer.write("import io.ringloom.framework.generated.GeneratedMessageDispatcher;\n");
                writer.write("import io.ringloom.framework.serialization.DecodeContext;\n");
                writer.write("import io.ringloom.framework.serialization.MessageDecoder;\n");
                writer.write("import io.ringloom.framework.serialization.SerializerRegistry;\n");
                writer.write("import io.ringloom.framework.status.RingloomHandlerStatus;\n");
                writer.write("import io.ringloom.service.RingloomMessage;\n");
                writer.write("import java.lang.foreign.MemorySegment;\n\n");
                writer.write("public final class " + dispatcherName + " implements GeneratedMessageDispatcher {\n");
                writer.write("  private SerializerRegistry serializers = SerializerRegistry.EMPTY;\n");
                writer.write("  private final DecodeContext decodeContext = new DecodeContext();\n");
                for (int i = 0; i < handlers.size(); i++) {
                    writer.write(
                            "  private final " + handlers.get(i).component().getQualifiedName() + " h" + i + " = new "
                                    + handlers.get(i).component().getQualifiedName() + "();\n");
                }
                writer.write("  public void initializeSerializers(SerializerRegistry serializers) {\n");
                writer.write(
                        "    this.serializers = java.util.Objects.requireNonNull(serializers, \"serializers\");\n");
                writer.write("  }\n");
                writer.write("  @Override public int onMessage(RingloomMessage message, MessageContext context) {\n");
                writer.write("    return switch (context.templateId()) {\n");
                for (int i = 0; i < handlers.size(); i++) {
                    Handler handler = handlers.get(i);
                    writer.write("      case " + handler.templateId() + " -> ");
                    writeHandlerCall(writer, "h" + i, handler.method());
                    if (!isBlockHandlerCall(handler.method())) {
                        writer.write(";");
                    }
                    writer.write("\n");
                }
                writer.write("      default -> RingloomHandlerStatus.UNKNOWN_TEMPLATE_ID;\n");
                writer.write("    };\n");
                writer.write("  }\n");
                writer.write("}\n");
            }
        } catch (IOException ex) {
            error(origin, "failed to generate dispatcher: " + ex.getMessage());
        }
    }

    private void writeHandlerCall(Writer writer, String field, ExecutableElement method) throws IOException {
        VariableElement payloadParameter = serializerPayloadParameter(method);
        if (payloadParameter != null) {
            writeSerializerHandlerCall(writer, field, method, payloadParameter);
            return;
        }
        if (!returnsInt(method)) {
            writer.write("{ " + field + "." + method.getSimpleName() + "(");
        } else {
            writer.write(field + "." + method.getSimpleName() + "(");
        }
        List<? extends VariableElement> parameters = method.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                writer.write(", ");
            }
            String type = parameters.get(i).asType().toString();
            if (type.equals("io.ringloom.service.RingloomMessage")) {
                writer.write("message");
            } else if (type.equals("io.ringloom.framework.dispatch.MessageContext")) {
                writer.write("context");
            } else if (type.equals("java.lang.foreign.MemorySegment")) {
                writer.write("context.payloadSegment()");
            }
        }
        writer.write(")");
        if (!returnsInt(method)) {
            writer.write("; yield RingloomHandlerStatus.OK; }");
        }
    }

    private void writeSerializerHandlerCall(
            Writer writer, String field, ExecutableElement method, VariableElement payloadParameter)
            throws IOException {
        RingloomHandler handler = method.getAnnotation(RingloomHandler.class);
        String payloadType = payloadParameter.asType().toString();
        writer.write("{ MessageDecoder<" + payloadType + "> decoder = serializers.decoder(\""
                + escape(handler.serializer()) + "\", " + payloadType + ".class);");
        writer.write(" if (decoder == null) yield RingloomHandlerStatus.SERIALIZATION_ERROR;");
        writer.write(" final " + payloadType + " decoded;");
        writer.write(
                " try { decoded = decoder.decode(decodeContext.buffer().wrap(context.payloadSegment()), decodeContext);");
        writer.write(" } catch (RuntimeException ex) { yield RingloomHandlerStatus.SERIALIZATION_ERROR; }");
        if (!returnsInt(method)) {
            writer.write(" " + field + "." + method.getSimpleName() + "(");
        } else {
            writer.write(" yield " + field + "." + method.getSimpleName() + "(");
        }
        List<? extends VariableElement> parameters = method.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                writer.write(", ");
            }
            VariableElement parameter = parameters.get(i);
            if (parameter == payloadParameter) {
                writer.write("decoded");
            } else {
                String type = parameter.asType().toString();
                if (type.equals("io.ringloom.service.RingloomMessage")) {
                    writer.write("message");
                } else if (type.equals("io.ringloom.framework.dispatch.MessageContext")) {
                    writer.write("context");
                } else if (type.equals("java.lang.foreign.MemorySegment")) {
                    writer.write("context.payloadSegment()");
                }
            }
        }
        writer.write(");");
        if (!returnsInt(method)) {
            writer.write(" yield RingloomHandlerStatus.OK;");
        }
        writer.write(" }");
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
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, origin);
            try (Writer writer = file.openWriter()) {
                if (!pkg.isEmpty()) {
                    writer.write("package " + pkg + ";\n\n");
                }
                writer.write("import io.ringloom.framework.RingloomRuntime;\n");
                writer.write("import io.ringloom.framework.generated.GeneratedClientBinding;\n");
                writer.write("import io.ringloom.framework.generated.GeneratedMessageDispatcher;\n");
                writer.write("import io.ringloom.framework.generated.GeneratedRingloomApplication;\n");
                writer.write("import io.ringloom.framework.serialization.SerializerRegistry;\n");
                writer.write("import java.util.List;\n\n");
                writer.write("public final class " + appName + " implements GeneratedRingloomApplication {\n");
                writer.write("  private final " + dispatcherName + " dispatcher = new " + dispatcherName + "();\n");
                writer.write("  @Override public String serviceName() { return \"" + service + "\"; }\n");
                writer.write("  @Override public List<GeneratedClientBinding<?>> clients() { return List.of(");
                for (int i = 0; i < clients.size(); i++) {
                    if (i > 0) {
                        writer.write(", ");
                    }
                    TypeElement client = clients.get(i);
                    String clientName = client.getQualifiedName().toString();
                    String generatedClient = clientName + "_RingloomClient";
                    String target = client.getAnnotation(RingloomClient.class).service();
                    writer.write("new GeneratedClientBinding<" + clientName + ">() {");
                    writer.write(" public Class<" + clientName + "> clientType() { return " + clientName + ".class; }");
                    writer.write(" public String targetServiceName() { return \"" + target + "\"; }");
                    writer.write(
                            " public " + clientName
                                    + " create(RingloomRuntime runtime, io.ringloom.service.RingloomClient lowLevelClient, SerializerRegistry serializers) {");
                    writer.write(" return new " + generatedClient + "(runtime, lowLevelClient, serializers); }");
                    writer.write("}");
                }
                writer.write("); }\n");
                writer.write(
                        "  @Override public void initializeSerializers(SerializerRegistry serializers) { dispatcher.initializeSerializers(serializers); }\n");
                writer.write("  @Override public GeneratedMessageDispatcher dispatcher() { return dispatcher; }\n");
                writer.write("}\n");
            }
        } catch (IOException ex) {
            error(origin, "failed to generate application: " + ex.getMessage());
        }
    }

    private void generateProvider(String pkg, String providerName, String appName, TypeElement origin) {
        String qualifiedName = pkg.isEmpty() ? providerName : pkg + "." + providerName;
        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, origin);
            try (Writer writer = file.openWriter()) {
                if (!pkg.isEmpty()) {
                    writer.write("package " + pkg + ";\n\n");
                }
                writer.write("import io.ringloom.framework.generated.GeneratedRingloomApplication;\n");
                writer.write("import io.ringloom.framework.generated.GeneratedRingloomApplicationProvider;\n\n");
                writer.write(
                        "public final class " + providerName + " implements GeneratedRingloomApplicationProvider {\n");
                writer.write("  @Override public GeneratedRingloomApplication application() { return new " + appName
                        + "(); }\n");
                writer.write("}\n");
            }
        } catch (IOException ex) {
            error(origin, "failed to generate provider: " + ex.getMessage());
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

    private boolean isBlockHandlerCall(ExecutableElement method) {
        return !returnsInt(method) || serializerPayloadParameter(method) != null;
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
