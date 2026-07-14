// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.generated;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.config.RingloomSerializerConfig;
import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.service.RingloomMessage;
import java.util.List;
import java.util.Map;

/**
 * SPI implemented by processor-generated application metadata classes.
 */
public interface GeneratedRingloomApplication {
    /**
     * Returns the RingLoom service name represented by this generated application.
     *
     * @return the generated service name
     */
    String serviceName();

    /**
     * Returns the generated client bindings exposed by the application.
     *
     * @return the generated client bindings
     */
    List<GeneratedClientBinding<?>> clients();

    /**
     * Returns the generated dispatcher used for inbound messages.
     *
     * @return the generated message dispatcher
     */
    GeneratedMessageDispatcher dispatcher();

    /**
     * Returns service component types consumed by the generated dispatcher.
     *
     * @return generated handler component types
     */
    default List<Class<?>> componentTypes() {
        return List.of();
    }

    /**
     * Returns an application instance backed by externally managed component instances.
     *
     * @param components component instances keyed by their concrete type
     * @return an application using the supplied components
     */
    default GeneratedRingloomApplication withComponents(Map<Class<?>, ?> components) {
        return this;
    }

    /**
     * Registers generated serializers into the supplied builder before runtime startup.
     *
     * @param builder the serializer registry builder to contribute to
     */
    default void registerSerializers(SerializerRegistry.Builder builder) {}

    /**
     * Registers generated serializers into the supplied builder with access to runtime serializer
     * configuration.
     *
     * @param builder the serializer registry builder to contribute to
     * @param serializers the serializer runtime configuration
     */
    default void registerSerializers(SerializerRegistry.Builder builder, RingloomSerializerConfig serializers) {
        registerSerializers(builder);
    }

    /**
     * Supplies the runtime serializer registry to generated dispatchers that decode inbound payloads.
     *
     * @param serializers the runtime serializer registry
     */
    default void initializeSerializers(SerializerRegistry serializers) {}

    /**
     * Returns generated partition-key extractors keyed by template id.
     *
     * @return the template-id to partition-key extractor map
     */
    default Map<Integer, GeneratedPartitionKeyExtractor> partitionKeyExtractors() {
        return Map.of();
    }

    /**
     * Returns whether generated partition-key extraction is available.
     *
     * @return {@code true} when the application has generated partition-key extractors
     */
    default boolean hasPartitionKeyExtractors() {
        return !partitionKeyExtractors().isEmpty();
    }

    /**
     * Extracts a generated partition key for a message template without boxed map lookup.
     *
     * @param templateId the inbound message template id
     * @param message the inbound message
     * @param context the mutable message context
     * @return the generated partition key
     */
    default long partitionKey(int templateId, RingloomMessage message, MessageContext context) {
        GeneratedPartitionKeyExtractor extractor = partitionKeyExtractors().get(templateId);
        if (extractor == null) {
            throw new IllegalStateException("missing partition-key extractor for template " + templateId);
        }
        return extractor.partitionKey(message, context);
    }

    /**
     * Returns whether the generated application requires a correlation-aware native send ABI.
     *
     * @return {@code true} when generated clients depend on correlation-aware sends
     */
    default boolean requiresCorrelationAwareSends() {
        return false;
    }

    /**
     * Returns the generated topic publisher bindings exposed by the application.
     *
     * @return the generated topic publisher bindings
     */
    default List<GeneratedTopicPublisherBinding> topicPublishers() {
        return List.of();
    }

    /**
     * Returns the generated topic handler bindings exposed by the application.
     *
     * @return the generated topic handler bindings
     */
    default List<GeneratedTopicHandlerBinding> topicHandlers() {
        return List.of();
    }

    /**
     * Returns the generated topic dispatcher, or {@code null} when there are no
     * {@code @RingloomTopicHandler} methods.
     *
     * @return the generated topic dispatcher, or {@code null}
     */
    default GeneratedTopicDispatcher topicDispatcher() {
        return null;
    }

    /**
     * Supplies the runtime-resolved broker topic ids, in declaration order, to the generated topic
     * dispatcher. The runtime invokes this after topic registration completes so the dispatcher's
     * topic-id switch can match inbound messages.
     *
     * @param resolvedTopicIds the broker-assigned topic ids in declaration order
     */
    default void initializeTopicIds(long[] resolvedTopicIds) {}

    /**
     * Returns generated partition-key extractors for topic handlers, keyed by broker-assigned topic id.
     *
     * <p>The keys are resolved at runtime (the broker assigns topic ids during publication/subscription
     * registration); the generated application populates the map during runtime startup.
     *
     * @return topic-id to partition-key extractor map
     */
    default Map<Long, GeneratedPartitionKeyExtractor> topicPartitionKeyExtractors() {
        return Map.of();
    }

    /**
     * Returns whether the generated application requires any topic bindings to be registered.
     *
     * @return {@code true} when the application has topic publishers or a topic dispatcher
     */
    default boolean requiresTopicBindings() {
        return !topicPublishers().isEmpty() || topicDispatcher() != null;
    }

    /**
     * Invoked after the runtime has started and all generated clients are available.
     *
     * @param runtime the started runtime
     */
    default void onRuntimeStarted(RingloomRuntime runtime) {}

    /**
     * Invoked while the runtime is shutting down.
     *
     * @param runtime the runtime being stopped
     */
    default void onRuntimeStopping(RingloomRuntime runtime) {}
}
