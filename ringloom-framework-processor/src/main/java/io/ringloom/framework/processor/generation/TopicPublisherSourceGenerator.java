// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.generation;

import io.ringloom.framework.processor.ProcessorContext;
import io.ringloom.framework.processor.TemplateRenderer;
import io.ringloom.framework.processor.model.SourceHelpers;
import io.ringloom.framework.processor.model.TopicPublish;
import io.ringloom.framework.processor.model.TopicPublisher;
import io.ringloom.service.TopicAckMode;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.lang.model.element.TypeElement;

/**
 * Generates the {@code <Name>_RingloomTopicPublisher} proxy for a
 * {@link io.ringloom.framework.annotation.RingloomTopicPublisher} interface.
 */
public final class TopicPublisherSourceGenerator {
    /** Default scratch-buffer capacity (bytes) for the per-thread encode buffer. */
    static final int DEFAULT_MAX_PAYLOAD_BYTES = 4096;

    private final ProcessorContext ctx;
    private final TemplateRenderer templates;

    public TopicPublisherSourceGenerator(ProcessorContext ctx, TemplateRenderer templates) {
        this.ctx = ctx;
        this.templates = templates;
    }

    /**
     * Generates the publisher proxy.
     *
     * @param topicPublisher the validated publisher model
     */
    public void generate(TopicPublisher topicPublisher) {
        TypeElement publisherInterface = topicPublisher.publisherInterface();
        String pkg = SourceHelpers.packageName(ctx.elementUtils(), publisherInterface);
        String simpleName = publisherInterface.getSimpleName().toString();
        String generatedName = simpleName + "_RingloomTopicPublisher";
        String qualifiedName = pkg.isEmpty() ? generatedName : pkg + "." + generatedName;
        try {
            StringBuilder methods = new StringBuilder();
            for (TopicPublish publish : topicPublisher.publishMethods()) {
                methods.append(methodSource(publish));
            }
            String source = templates.render(
                    "topic-publisher.java.mustache",
                    buildModel(pkg, generatedName, simpleName, topicPublisher, methods.toString()));
            new SourceWriter(ctx).writeSourceFile(qualifiedName, publisherInterface, source);
        } catch (IOException ex) {
            ctx.error(publisherInterface, "failed to generate topic publisher: " + ex.getMessage());
        }
    }

    private Map<String, Object> buildModel(
            String pkg, String generatedName, String simpleName, TopicPublisher topicPublisher, String methodSources) {
        Map<String, Object> model = new HashMap<>();
        model.put("packageName", pkg);
        model.put("generatedName", generatedName);
        model.put("simpleName", simpleName);
        model.put("topicName", SourceHelpers.escape(topicPublisher.annotation().topic()));
        model.put("methodSources", methodSources);
        model.put("maxPayloadBytes", DEFAULT_MAX_PAYLOAD_BYTES);
        return model;
    }

    private String methodSource(TopicPublish publish) throws IOException {
        Map<String, Object> model = new HashMap<>();
        model.put("methodName", publish.method().getSimpleName().toString());
        model.put("payloadType", publish.payloadType());
        model.put("payloadName", publish.payloadParameter().getSimpleName().toString());
        model.put("serializer", SourceHelpers.escape(publish.annotation().serializer()));
        if (publish.annotation().ackMode() == TopicAckMode.REPLICATE_ONCE && publish.ackCallback()) {
            return templates.render("topic-publisher-publish-ack-method.java.mustache", model);
        }
        return templates.render("topic-publisher-publish-method.java.mustache", model);
    }
}
