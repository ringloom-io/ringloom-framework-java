// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.generation;

import io.ringloom.framework.annotation.RequestMode;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.annotation.RingloomSchedule;
import io.ringloom.framework.processor.ProcessorContext;
import io.ringloom.framework.processor.TemplateRenderer;
import io.ringloom.framework.processor.model.Handler;
import io.ringloom.framework.processor.model.KvMethod;
import io.ringloom.framework.processor.model.KvStore;
import io.ringloom.framework.processor.model.KvWriterStateHandler;
import io.ringloom.framework.processor.model.PartitionKey;
import io.ringloom.framework.processor.model.SbePayloadModel;
import io.ringloom.framework.processor.model.Schedule;
import io.ringloom.framework.processor.model.SourceHelpers;
import io.ringloom.framework.processor.model.Symbols;
import io.ringloom.framework.processor.validation.ClientValidator;
import io.ringloom.framework.processor.validation.HandlerValidator;
import io.ringloom.framework.processor.validation.SbeValidator;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

public final class ApplicationSourceGenerator {

    private final ProcessorContext ctx;
    private final TemplateRenderer templates;

    public ApplicationSourceGenerator(ProcessorContext ctx, TemplateRenderer templates) {
        this.ctx = ctx;
        this.templates = templates;
    }

    public void generate(
            String pkg,
            String appName,
            String dispatcherName,
            String service,
            List<TypeElement> clients,
            List<TypeElement> components,
            List<Handler> handlers,
            List<Schedule> schedules,
            TypeElement origin) {
        generate(
                pkg,
                appName,
                dispatcherName,
                service,
                clients,
                components,
                handlers,
                schedules,
                List.of(),
                List.of(),
                null,
                origin);
    }

    /**
     * Generates the application metadata, including optional topic bindings.
     *
     * @param topicPublishers        validated topic publishers (may be empty)
     * @param topicHandlers          validated topic handlers (may be empty)
     * @param topicDispatcherName    the generated topic dispatcher class name, or {@code null} when none
     */
    public void generate(
            String pkg,
            String appName,
            String dispatcherName,
            String service,
            List<TypeElement> clients,
            List<TypeElement> components,
            List<Handler> handlers,
            List<Schedule> schedules,
            List<io.ringloom.framework.processor.model.TopicPublisher> topicPublishers,
            List<io.ringloom.framework.processor.model.TopicHandler> topicHandlers,
            String topicDispatcherName,
            TypeElement origin) {
        generate(
                pkg,
                appName,
                dispatcherName,
                service,
                clients,
                components,
                handlers,
                schedules,
                topicPublishers,
                topicHandlers,
                topicDispatcherName,
                List.of(),
                List.of(),
                origin);
    }

    /**
     * Generates the application metadata, including optional topic and KV store bindings.
     *
     * @param kvStores               validated KV store interfaces (may be empty)
     * @param kvWriterStateHandlers  validated KV writer state handlers (may be empty)
     */
    public void generate(
            String pkg,
            String appName,
            String dispatcherName,
            String service,
            List<TypeElement> clients,
            List<TypeElement> components,
            List<Handler> handlers,
            List<Schedule> schedules,
            List<io.ringloom.framework.processor.model.TopicPublisher> topicPublishers,
            List<io.ringloom.framework.processor.model.TopicHandler> topicHandlers,
            String topicDispatcherName,
            List<KvStore> kvStores,
            List<KvWriterStateHandler> kvWriterStateHandlers,
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
                                SourceHelpers.escape(client.getAnnotation(RingloomClient.class)
                                        .service()),
                                "comma",
                                i + 1 == clients.size() ? "" : ",")));
            }
            Map<String, Object> model = new java.util.HashMap<>();
            model.put("packageName", pkg);
            model.put("appName", appName);
            model.put("dispatcherName", dispatcherName);
            model.put("serviceName", SourceHelpers.escape(service));
            model.put("clientBindingSources", clientBindings.toString());
            model.put("componentTypeSources", componentTypeSources(components));
            model.put("serializerRegistrationSources", serializerRegistrationSources(clients, handlers, kvStores));
            model.put("partitionKeyExtractorSources", partitionKeyExtractorSources(handlers));
            model.put("scheduleRegistrationSources", scheduleRegistrationSources(schedules));
            // Topic bindings.
            boolean hasTopicPublishers = !topicPublishers.isEmpty();
            boolean hasTopicHandlers = !topicHandlers.isEmpty();
            boolean hasTopicDispatcher = topicDispatcherName != null;
            model.put("hasTopicBindings", hasTopicPublishers);
            model.put("hasTopicHandlers", hasTopicHandlers);
            model.put("hasTopicDispatcher", hasTopicDispatcher);
            model.put("topicDispatcherName", topicDispatcherName == null ? "" : topicDispatcherName);
            model.put("topicPublisherBindingSources", topicPublisherBindings(topicPublishers));
            model.put("topicHandlerBindingSources", topicHandlerBindings(topicHandlers));
            model.put("initialTopicIds", initialTopicIds(topicHandlers));
            model.put("hasKvStores", !kvStores.isEmpty());
            model.put("kvStoreBindingSources", kvStoreBindings(kvStores));
            model.put("hasKvWriterStateHandlers", !kvWriterStateHandlers.isEmpty());
            model.put("kvWriterStateCases", kvWriterStateCases(kvWriterStateHandlers));
            new SourceWriter(ctx)
                    .writeSourceFile(qualifiedName, origin, templates.render("application.java.mustache", model));
        } catch (IOException ex) {
            ctx.error(origin, "failed to generate application: " + ex.getMessage());
        }
    }

    private String kvStoreBindings(List<KvStore> kvStores) throws IOException {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < kvStores.size(); i++) {
            KvStore store = kvStores.get(i);
            String storeType = store.storeInterface().getQualifiedName().toString();
            String pkg = SourceHelpers.packageName(ctx.elementUtils(), store.storeInterface());
            Map<String, Object> model = new java.util.HashMap<>();
            model.put("storeType", storeType);
            model.put("storeName", SourceHelpers.escape(store.name()));
            model.put("reads", store.reads());
            model.put("writes", store.writes());
            model.put(
                    "maxKeyLength",
                    store.maxKeyLength() > 0 ? String.valueOf(store.maxKeyLength()) : "defaults.maxKeyLength()");
            model.put(
                    "maxValueLength",
                    store.maxValueLength() >= 0 ? store.maxValueLength() + "L" : "defaults.maxValueLength()");
            model.put(
                    "defaultTtlMillis",
                    store.defaultTtlMillis() >= 0 ? store.defaultTtlMillis() + "L" : "defaults.defaultTtlMillis()");
            model.put("mapSizeHint", store.mapSizeHint() >= 0 ? store.mapSizeHint() + "L" : "defaults.mapSizeHint()");
            model.put(
                    "rollScheme",
                    store.rollScheme().isEmpty()
                            ? "defaults.topicRollScheme()"
                            : "\"" + SourceHelpers.escape(store.rollScheme()) + "\"");
            model.put("keyLengthHint", store.keyLengthHint());
            model.put(
                    "generatedStoreName",
                    pkg.isEmpty() ? store.generatedSimpleName() : pkg + "." + store.generatedSimpleName());
            model.put("comma", i + 1 == kvStores.size() ? "" : ",");
            out.append(templates.render("application-kv-store-binding.java.mustache", model));
        }
        return out.toString();
    }

    private static String kvWriterStateCases(List<KvWriterStateHandler> handlers) {
        Map<String, StringBuilder> byStore = new java.util.TreeMap<>();
        for (KvWriterStateHandler handler : handlers) {
            byStore.computeIfAbsent(handler.store(), ignored -> new StringBuilder())
                    .append("        try {\n")
                    .append("          component(")
                    .append(handler.component().getQualifiedName())
                    .append(".class).")
                    .append(handler.method().getSimpleName())
                    .append("(event);\n")
                    .append("        } catch (RuntimeException ex) {\n")
                    .append("          if (failure == null) {\n")
                    .append("            failure = ex;\n")
                    .append("          }\n")
                    .append("        }\n");
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, StringBuilder> entry : byStore.entrySet()) {
            out.append("      case \"")
                    .append(SourceHelpers.escape(entry.getKey()))
                    .append("\" -> {\n")
                    .append(entry.getValue())
                    .append("      }\n");
        }
        return out.toString();
    }

    private String topicPublisherBindings(List<io.ringloom.framework.processor.model.TopicPublisher> topicPublishers)
            throws IOException {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < topicPublishers.size(); i++) {
            io.ringloom.framework.processor.model.TopicPublisher publisher = topicPublishers.get(i);
            String publisherType =
                    publisher.publisherInterface().getQualifiedName().toString();
            io.ringloom.framework.annotation.RingloomTopicPublisher annotation = publisher.annotation();
            out.append(templates.render(
                    "application-topic-publisher-binding.java.mustache",
                    Map.of(
                            "publisherType",
                            publisherType,
                            "generatedPublisherName",
                            publisher.publisherInterface().getSimpleName() + "_RingloomTopicPublisher",
                            "topicName",
                            SourceHelpers.escape(annotation.topic()),
                            "clientAlias",
                            SourceHelpers.escape(annotation.client()),
                            "rollScheme",
                            SourceHelpers.escape(annotation.rollScheme()),
                            "retentionCycles",
                            annotation.retentionCycles(),
                            "comma",
                            i + 1 == topicPublishers.size() ? "" : ",")));
        }
        return out.toString();
    }

    private String topicHandlerBindings(List<io.ringloom.framework.processor.model.TopicHandler> topicHandlers)
            throws IOException {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < topicHandlers.size(); i++) {
            io.ringloom.framework.processor.model.TopicHandler handler = topicHandlers.get(i);
            io.ringloom.framework.annotation.RingloomTopicHandler annotation = handler.annotation();
            String partitionKey = handler.partitionKey();
            out.append(templates.render(
                    "application-topic-handler-binding.java.mustache",
                    Map.of(
                            "topicName",
                            SourceHelpers.escape(annotation.topic()),
                            "start",
                            annotation.start().name(),
                            "serializer",
                            SourceHelpers.escape(annotation.serializer()),
                            "partitionKey",
                            partitionKey == null ? "" : SourceHelpers.escape(partitionKey),
                            "comma",
                            i + 1 == topicHandlers.size() ? "" : ",")));
        }
        return out.toString();
    }

    private String initialTopicIds(List<io.ringloom.framework.processor.model.TopicHandler> topicHandlers) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (io.ringloom.framework.processor.model.TopicHandler handler : topicHandlers) {
            seen.add(handler.annotation().topic());
        }
        java.util.List<String> zeros = new java.util.ArrayList<>();
        for (int i = 0; i < seen.size(); i++) {
            zeros.add("0L");
        }
        return String.join(", ", zeros);
    }

    public String serializerRegistrationSources(List<TypeElement> clients, List<Handler> handlers) {
        return serializerRegistrationSources(clients, handlers, List.of());
    }

    /**
     * Renders serializer registrations for clients, handlers, and KV store value/key types.
     *
     * @param clients  the client interfaces
     * @param handlers the handlers
     * @param kvStores the KV store interfaces
     * @return the registration source
     */
    public String serializerRegistrationSources(
            List<TypeElement> clients, List<Handler> handlers, List<KvStore> kvStores) {
        Map<String, String> registrations = new LinkedHashMap<>();
        Set<String> sbeEncoderTypes = new java.util.HashSet<>();
        Set<String> sbeDecoderTypes = new java.util.HashSet<>();
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
                if (ClientValidator.isMemorySegmentOnly(method)) {
                    continue;
                }
                VariableElement payloadParameter = ClientValidator.clientPayloadParameter(method);
                String payloadType = payloadParameter.asType().toString();
                collectForyClientTypes(method, request, payloadParameter, explicitForyTypes, defaultForyTypes);
                if (!SbePayloadModel.usesSbe(request.serializer(), payloadType)) {
                    continue;
                }
                sbeEncoderTypes.add(payloadType);
                registrations.putIfAbsent(
                        "client|sbe|" + request.templateId() + "|" + payloadType,
                        sbeClientRegistrationSource(Symbols.SBE_SERIALIZER, request.templateId(), payloadType));
            }
        }
        for (Handler handler : handlers) {
            VariableElement payloadParameter = HandlerValidator.serializerPayloadParameter(handler.method());
            if (payloadParameter == null) {
                continue;
            }
            RingloomHandler annotation = handler.method().getAnnotation(RingloomHandler.class);
            if (annotation == null) {
                continue;
            }
            String payloadType = payloadParameter.asType().toString();
            collectForyTypes(annotation.serializer(), payloadParameter.asType(), explicitForyTypes, defaultForyTypes);
            if (!SbePayloadModel.usesSbe(annotation.serializer(), payloadType)) {
                continue;
            }
            sbeDecoderTypes.add(payloadType);
            registrations.putIfAbsent(
                    "handler|sbe|" + handler.templateId() + "|" + payloadType,
                    sbeHandlerRegistrationSource(Symbols.SBE_SERIALIZER, handler.templateId(), payloadType));
        }
        collectKvRegistrations(
                kvStores, registrations, sbeEncoderTypes, sbeDecoderTypes, explicitForyTypes, defaultForyTypes);
        StringBuilder out = new StringBuilder();
        for (String source : registrations.values()) {
            out.append(source);
        }
        out.append(foryRegistrationSource(explicitForyTypes, defaultForyTypes));
        return out.toString();
    }

    /**
     * KV values have no template id; SBE codecs are registered with template id {@code 0} unless a
     * client or handler already registers the same type.
     */
    private void collectKvRegistrations(
            List<KvStore> kvStores,
            Map<String, String> registrations,
            Set<String> sbeEncoderTypes,
            Set<String> sbeDecoderTypes,
            Set<String> explicitForyTypes,
            Set<String> defaultForyTypes) {
        for (KvStore store : kvStores) {
            if (store.keyKind() == KvStore.KeyKind.SERIALIZER) {
                TypeElement keyElement = ctx.elementUtils().getTypeElement(store.keyType());
                if (keyElement != null) {
                    collectForyTypes(store.keySerializer(), keyElement.asType(), explicitForyTypes, defaultForyTypes);
                }
            }
            for (KvMethod method : store.methods()) {
                if (!method.hasCodec()) {
                    continue;
                }
                String valueType = method.valueType();
                if (SbePayloadModel.usesSbe(method.serializer(), valueType)) {
                    if (method.shape() == KvMethod.Shape.PUT) {
                        if (sbeEncoderTypes.add(valueType)) {
                            registrations.putIfAbsent(
                                    "kv|sbe|encoder|" + valueType,
                                    sbeClientRegistrationSource(Symbols.SBE_SERIALIZER, 0, valueType));
                        }
                    } else if (sbeDecoderTypes.add(valueType)) {
                        registrations.putIfAbsent(
                                "kv|sbe|decoder|" + valueType,
                                sbeHandlerRegistrationSource(Symbols.SBE_SERIALIZER, 0, valueType));
                    }
                    continue;
                }
                TypeElement valueElement = ctx.elementUtils().getTypeElement(valueType);
                if (valueElement != null) {
                    collectForyTypes(method.serializer(), valueElement.asType(), explicitForyTypes, defaultForyTypes);
                }
            }
        }
    }

    private void collectForyClientTypes(
            ExecutableElement method,
            RingloomRequest request,
            VariableElement payloadParameter,
            Set<String> explicitForyTypes,
            Set<String> defaultForyTypes) {
        collectForyTypes(request.serializer(), payloadParameter.asType(), explicitForyTypes, defaultForyTypes);
        if (request.mode() == RequestMode.VIRTUAL_THREAD_BLOCKING) {
            collectForyTypes(request.serializer(), method.getReturnType(), explicitForyTypes, defaultForyTypes);
        }
    }

    private void collectForyTypes(
            String serializer, TypeMirror type, Set<String> explicitForyTypes, Set<String> defaultForyTypes) {
        if (serializer.equals(Symbols.FORY_SERIALIZER)) {
            collectForyTypeGraph(type, explicitForyTypes);
            return;
        }
        if (serializer.isBlank() && !SbePayloadModel.usesSbe(serializer, type.toString())) {
            collectForyTypeGraph(type, defaultForyTypes);
        }
    }

    private void collectForyTypeGraph(TypeMirror type, Set<String> result) {
        TypeKind kind = type.getKind();
        if (kind.isPrimitive()
                || kind == TypeKind.VOID
                || kind == TypeKind.NULL
                || kind == TypeKind.NONE
                || kind == TypeKind.WILDCARD) {
            return;
        }
        if (type instanceof ArrayType arrayType) {
            collectForyTypeGraph(arrayType.getComponentType(), result);
            return;
        }
        if (!(type instanceof DeclaredType declaredType)
                || !(declaredType.asElement() instanceof TypeElement typeElement)) {
            return;
        }
        String typeName = typeElement.getQualifiedName().toString();
        for (TypeMirror argument : declaredType.getTypeArguments()) {
            collectForyTypeGraph(argument, result);
        }
        if (typeName.startsWith("java.")) {
            return;
        }
        result.add(typeName);
        if (typeElement.getKind() == ElementKind.RECORD) {
            for (RecordComponentElement component : typeElement.getRecordComponents()) {
                collectForyTypeGraph(component.asType(), result);
            }
        }
    }

    public String foryRegistrationSource(Set<String> explicitForyTypes, Set<String> defaultForyTypes) {
        Set<String> allTypes = new LinkedHashSet<>();
        allTypes.addAll(explicitForyTypes);
        allTypes.addAll(defaultForyTypes);
        if (allTypes.isEmpty()) {
            return "";
        }
        try {
            return templates.render(
                    "application-fory-registration.java.mustache",
                    java.util.Map.of(
                            "explicitTypes",
                            new java.util.ArrayList<>(explicitForyTypes),
                            "hasDefaultTypes",
                            !defaultForyTypes.isEmpty(),
                            "defaultTypes",
                            new java.util.ArrayList<>(defaultForyTypes)));
        } catch (java.io.IOException ex) {
            ctx.error(null, "failed to render fory registration: " + ex.getMessage());
            return "";
        }
    }

    public String sbeClientRegistrationSource(String serializer, int templateId, String payloadType) {
        SbePayloadModel payload = SbeValidator.requireSbeDtoPayload(payloadType, null, ctx.elementUtils(), ctx);
        try {
            return templates.render(
                    "application-sbe-client-registration.java.mustache",
                    java.util.Map.of(
                            "serializer",
                            SourceHelpers.escape(serializer),
                            "dtoType",
                            payload.dtoType(),
                            "templateId",
                            templateId,
                            "encoderType",
                            payload.encoderType()));
        } catch (java.io.IOException ex) {
            ctx.error(null, "failed to render sbe client registration: " + ex.getMessage());
            return "";
        }
    }

    public String sbeHandlerRegistrationSource(String serializer, int templateId, String payloadType) {
        SbePayloadModel payload = SbeValidator.requireSbePayload(payloadType, null, ctx.elementUtils(), ctx);
        try {
            return templates.render(
                    "application-sbe-handler-registration.java.mustache",
                    java.util.Map.of(
                            "serializer",
                            SourceHelpers.escape(serializer),
                            "decoderType",
                            payload.decoderType(),
                            "dtoType",
                            payload.dtoType(),
                            "templateId",
                            templateId,
                            "decoderPayload",
                            payload.decoderPayload()));
        } catch (java.io.IOException ex) {
            ctx.error(null, "failed to render sbe handler registration: " + ex.getMessage());
            return "";
        }
    }

    public String partitionKeyExtractorSources(List<Handler> handlers) {
        StringBuilder entries = new StringBuilder();
        StringBuilder cases = new StringBuilder();
        StringBuilder methodsSource = new StringBuilder();
        int index = 0;
        for (Handler handler : handlers) {
            if (handler.partitionKey() == null) {
                continue;
            }
            if (index > 0) {
                entries.append(",\n");
            }
            String methodName = "partitionKey" + handler.templateId();
            entries.append("        ")
                    .append(handler.templateId())
                    .append(", this::")
                    .append(methodName);
            cases.append("      case ")
                    .append(handler.templateId())
                    .append(" -> ")
                    .append(methodName)
                    .append("(message, context);\n");
            methodsSource.append(partitionKeyMethodSource(methodName, handler));
            index++;
        }
        if (index == 0) {
            return "";
        }
        try {
            return templates.render(
                    "application-partition-key-extractors.java.mustache",
                    java.util.Map.of(
                            "casesSource",
                            cases.toString(),
                            "entriesSource",
                            entries.toString(),
                            "methodsSource",
                            methodsSource.toString()));
        } catch (java.io.IOException ex) {
            ctx.error(null, "failed to render partition key extractors: " + ex.getMessage());
            return "";
        }
    }

    public String partitionKeyMethodSource(String methodName, Handler handler) {
        PartitionKey partitionKey = handler.partitionKey();
        VariableElement parameter = partitionKey.parameter();
        String type = parameter.asType().toString();
        try {
            if (type.equals(Symbols.RINGLOOM_MESSAGE)) {
                return templates.render(
                        "application-partition-key-message-method.java.mustache",
                        java.util.Map.of("methodName", methodName));
            }
            if (type.equals(Symbols.MESSAGE_CONTEXT)) {
                return templates.render(
                        "application-partition-key-context-method.java.mustache",
                        java.util.Map.of("methodName", methodName));
            }
            RingloomHandler annotation = handler.method().getAnnotation(RingloomHandler.class);
            SbeValidator.requireSbePayload(type, parameter, ctx.elementUtils(), ctx);
            return templates.render(
                    "application-partition-key-sbe-method.java.mustache",
                    java.util.Map.of(
                            "methodName",
                            methodName,
                            "serializer",
                            SourceHelpers.escape(annotation.serializer()),
                            "type",
                            type,
                            "templateId",
                            handler.templateId(),
                            "accessor",
                            partitionKey.accessor()));
        } catch (java.io.IOException ex) {
            ctx.error(null, "failed to render partition key method: " + ex.getMessage());
            return "";
        }
    }

    public String componentTypeSources(List<TypeElement> components) {
        Map<String, String> componentTypes = new LinkedHashMap<>();
        for (TypeElement component : components) {
            String type = component.getQualifiedName().toString();
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

    public String scheduleRegistrationSources(List<Schedule> schedules) {
        if (schedules.isEmpty()) {
            return "";
        }
        StringBuilder perScheduleSources = new StringBuilder();
        for (Schedule schedule : schedules) {
            RingloomSchedule annotation = schedule.annotation();
            String componentType = schedule.component().getQualifiedName().toString();
            String methodName = schedule.method().getSimpleName().toString();
            if (annotation.fixedRateMillis() > 0) {
                perScheduleSources.append("""
                            runtime.scheduler().scheduleAtFixedRate(%dL, %dL, java.util.concurrent.TimeUnit.MILLISECONDS,
                                ignored -> component(%s.class).%s());
                    """.formatted(
                        annotation.initialDelayMillis(), annotation.fixedRateMillis(), componentType, methodName));
            } else {
                perScheduleSources.append("""
                            runtime.scheduler().scheduleWithFixedDelay(%dL, %dL, java.util.concurrent.TimeUnit.MILLISECONDS,
                                ignored -> component(%s.class).%s());
                    """.formatted(
                        annotation.initialDelayMillis(), annotation.fixedDelayMillis(), componentType, methodName));
            }
        }
        try {
            return templates.render(
                    "application-schedule-registration.java.mustache",
                    java.util.Map.of("scheduleSources", perScheduleSources.toString()));
        } catch (java.io.IOException ex) {
            ctx.error(null, "failed to render schedule registration: " + ex.getMessage());
            return "";
        }
    }
}
