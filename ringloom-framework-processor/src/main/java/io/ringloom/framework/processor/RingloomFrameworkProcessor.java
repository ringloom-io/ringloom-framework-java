// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor;

import io.ringloom.framework.annotation.RingloomApplication;
import io.ringloom.framework.annotation.RingloomClient;
import io.ringloom.framework.annotation.RingloomHandler;
import io.ringloom.framework.annotation.RingloomPartitionKey;
import io.ringloom.framework.annotation.RingloomRequest;
import io.ringloom.framework.annotation.RingloomSchedule;
import io.ringloom.framework.annotation.RingloomServiceComponent;
import io.ringloom.framework.annotation.RingloomTopicHandler;
import io.ringloom.framework.annotation.RingloomTopicPublish;
import io.ringloom.framework.annotation.RingloomTopicPublisher;
import io.ringloom.framework.processor.generation.ApplicationSourceGenerator;
import io.ringloom.framework.processor.generation.ClientSourceGenerator;
import io.ringloom.framework.processor.generation.DispatcherSourceGenerator;
import io.ringloom.framework.processor.generation.ProviderSourceGenerator;
import io.ringloom.framework.processor.generation.SourceWriter;
import io.ringloom.framework.processor.generation.TopicDispatcherSourceGenerator;
import io.ringloom.framework.processor.generation.TopicPublisherSourceGenerator;
import io.ringloom.framework.processor.model.Handler;
import io.ringloom.framework.processor.model.Schedule;
import io.ringloom.framework.processor.model.SourceHelpers;
import io.ringloom.framework.processor.model.TopicHandler;
import io.ringloom.framework.processor.model.TopicPublisher;
import io.ringloom.framework.processor.validation.ClientValidator;
import io.ringloom.framework.processor.validation.HandlerValidator;
import io.ringloom.framework.processor.validation.PartitionKeyValidator;
import io.ringloom.framework.processor.validation.ScheduleValidator;
import io.ringloom.framework.processor.validation.TemplateRules;
import io.ringloom.framework.processor.validation.TopicHandlerValidator;
import io.ringloom.framework.processor.validation.TopicPublisherValidator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

@SupportedSourceVersion(SourceVersion.RELEASE_25)
/**
 * Annotation processor that generates RingLoom clients, dispatchers, and application metadata.
 */
public final class RingloomFrameworkProcessor extends javax.annotation.processing.AbstractProcessor {

    private final TemplateRenderer templates = new TemplateRenderer();
    private ProcessorContext ctx;
    private boolean generated;

    private ClientValidator clientValidator;
    private HandlerValidator handlerValidator;
    private ScheduleValidator scheduleValidator;
    private PartitionKeyValidator partitionKeyValidator;
    private TopicPublisherValidator topicPublisherValidator;
    private TopicHandlerValidator topicHandlerValidator;
    private ClientSourceGenerator clientGenerator;
    private DispatcherSourceGenerator dispatcherGenerator;
    private ApplicationSourceGenerator applicationGenerator;
    private ProviderSourceGenerator providerGenerator;
    private TopicPublisherSourceGenerator topicPublisherGenerator;
    private TopicDispatcherSourceGenerator topicDispatcherGenerator;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(
                RingloomApplication.class.getCanonicalName(),
                RingloomClient.class.getCanonicalName(),
                RingloomRequest.class.getCanonicalName(),
                RingloomServiceComponent.class.getCanonicalName(),
                RingloomHandler.class.getCanonicalName(),
                RingloomPartitionKey.class.getCanonicalName(),
                RingloomSchedule.class.getCanonicalName(),
                RingloomTopicPublisher.class.getCanonicalName(),
                RingloomTopicPublish.class.getCanonicalName(),
                RingloomTopicHandler.class.getCanonicalName());
    }

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.ctx = new ProcessorContext(env);
        this.clientValidator = new ClientValidator(ctx);
        this.handlerValidator = new HandlerValidator(ctx);
        this.scheduleValidator = new ScheduleValidator(ctx);
        this.partitionKeyValidator = new PartitionKeyValidator(ctx);
        this.topicPublisherValidator = new TopicPublisherValidator(ctx);
        this.topicHandlerValidator = new TopicHandlerValidator(ctx);
        this.clientGenerator = new ClientSourceGenerator(ctx, templates);
        this.dispatcherGenerator = new DispatcherSourceGenerator(ctx, templates);
        this.applicationGenerator = new ApplicationSourceGenerator(ctx, templates);
        this.providerGenerator = new ProviderSourceGenerator(ctx, templates);
        this.topicPublisherGenerator = new TopicPublisherSourceGenerator(ctx, templates);
        this.topicDispatcherGenerator = new TopicDispatcherSourceGenerator(ctx, templates);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || generated) {
            return false;
        }
        List<TypeElement> clients = SourceHelpers.types(roundEnv.getElementsAnnotatedWith(RingloomClient.class));
        List<TypeElement> components = componentTypes(roundEnv);
        List<TypeElement> applications =
                SourceHelpers.types(roundEnv.getElementsAnnotatedWith(RingloomApplication.class));
        List<TypeElement> topicPublisherInterfaces =
                SourceHelpers.types(roundEnv.getElementsAnnotatedWith(RingloomTopicPublisher.class));
        clients.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        components.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        applications.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        topicPublisherInterfaces.sort(
                Comparator.comparing(t -> t.getQualifiedName().toString()));

        List<TopicPublisher> topicPublishers = new ArrayList<>();
        for (TypeElement publisherInterface : topicPublisherInterfaces) {
            TopicPublisher topicPublisher = topicPublisherValidator.validate(publisherInterface);
            topicPublishers.add(topicPublisher);
            topicPublisherGenerator.generate(topicPublisher);
        }
        for (TypeElement client : clients) {
            clientValidator.validate(client);
            clientGenerator.generate(client);
        }
        if (!components.isEmpty() || !applications.isEmpty()) {
            TypeElement application = applications.isEmpty() ? components.getFirst() : applications.getFirst();
            if (applications.size() > 1) {
                ctx.error(
                        applications.get(1),
                        "multiple @RingloomApplication types require an explicit single application");
                return true;
            }
            generateApplication(application, clients, components, topicPublishers);
        }
        generated = true;
        return false;
    }

    private void generateApplication(
            TypeElement application,
            List<TypeElement> clients,
            List<TypeElement> components,
            List<TopicPublisher> topicPublishers) {
        Map<Integer, ExecutableElement> handlerTemplateIds = new HashMap<>();
        List<Handler> handlers = new ArrayList<>();
        List<Schedule> schedules = new ArrayList<>();
        List<TopicHandler> topicHandlers = new ArrayList<>();
        for (TypeElement component : components) {
            for (Element enclosed : component.getEnclosedElements()) {
                RingloomHandler handlerAnnotation = enclosed.getAnnotation(RingloomHandler.class);
                if (handlerAnnotation != null) {
                    if (!(enclosed instanceof ExecutableElement method)) {
                        continue;
                    }
                    TemplateRules.validate(method, handlerAnnotation.templateId(), ctx);
                    ExecutableElement previous = handlerTemplateIds.putIfAbsent(handlerAnnotation.templateId(), method);
                    if (previous != null) {
                        ctx.error(method, "duplicate RingLoom handler template id " + handlerAnnotation.templateId());
                    }
                    handlerValidator.validate(method);
                    handlers.add(new Handler(
                            component, method, handlerAnnotation.templateId(), partitionKeyValidator.resolve(method)));
                }
                RingloomSchedule scheduleAnnotation = enclosed.getAnnotation(RingloomSchedule.class);
                if (scheduleAnnotation != null && enclosed instanceof ExecutableElement method) {
                    scheduleValidator.validate(method, scheduleAnnotation);
                    schedules.add(new Schedule(component, method, scheduleAnnotation));
                }
                RingloomTopicHandler topicHandlerAnnotation = enclosed.getAnnotation(RingloomTopicHandler.class);
                if (topicHandlerAnnotation != null && enclosed instanceof ExecutableElement method) {
                    topicHandlers.add(topicHandlerValidator.validate(component, method, topicHandlerAnnotation));
                }
            }
        }
        handlers.sort(Comparator.comparingInt(Handler::templateId));
        schedules.sort(Comparator.comparing(schedule -> schedule.component().getQualifiedName() + "."
                + schedule.method().getSimpleName()));
        topicHandlers.sort(Comparator.comparing(h -> h.annotation().topic()));

        String pkg = SourceHelpers.packageName(ctx.elementUtils(), application);
        String appSimple = application.getSimpleName().toString();
        String service = SourceHelpers.serviceName(application, appSimple);
        String dispatcherName = appSimple + "_RingloomDispatcher";
        String appName = appSimple + "_RingloomApplication";
        String providerName = appSimple + "_RingloomApplicationProvider";
        String topicDispatcherName = topicHandlers.isEmpty() ? null : appSimple + "_RingloomTopicDispatcher";
        dispatcherGenerator.generate(pkg, dispatcherName, handlers, application);
        if (topicDispatcherName != null) {
            topicDispatcherGenerator.generate(pkg, topicDispatcherName, topicHandlers, application);
        }
        applicationGenerator.generate(
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
                application);
        providerGenerator.generate(pkg, providerName, appName, application);
        new SourceWriter(ctx).writeServiceFile(pkg, providerName);
    }

    private List<TypeElement> componentTypes(RoundEnvironment roundEnv) {
        Map<String, TypeElement> result = new LinkedHashMap<>();
        for (TypeElement component :
                SourceHelpers.types(roundEnv.getElementsAnnotatedWith(RingloomServiceComponent.class))) {
            result.put(component.getQualifiedName().toString(), component);
        }
        for (Element handler : roundEnv.getElementsAnnotatedWith(RingloomHandler.class)) {
            Element enclosing = handler.getEnclosingElement();
            if (enclosing instanceof TypeElement component) {
                result.putIfAbsent(component.getQualifiedName().toString(), component);
            }
        }
        for (Element schedule : roundEnv.getElementsAnnotatedWith(RingloomSchedule.class)) {
            Element enclosing = schedule.getEnclosingElement();
            if (enclosing instanceof TypeElement component) {
                result.putIfAbsent(component.getQualifiedName().toString(), component);
            }
        }
        for (Element topicHandler : roundEnv.getElementsAnnotatedWith(RingloomTopicHandler.class)) {
            Element enclosing = topicHandler.getEnclosingElement();
            if (enclosing instanceof TypeElement component) {
                result.putIfAbsent(component.getQualifiedName().toString(), component);
            }
        }
        return new ArrayList<>(result.values());
    }
}
