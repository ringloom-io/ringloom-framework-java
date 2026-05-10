// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.generated;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.serialization.SerializerRegistry;
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
     * Returns whether the generated application requires a correlation-aware native send ABI.
     *
     * @return {@code true} when generated clients depend on correlation-aware sends
     */
    default boolean requiresCorrelationAwareSends() {
        return false;
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
