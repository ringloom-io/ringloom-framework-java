// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.generated;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.framework.topic.ack.TopicAckRegistry;
import io.ringloom.service.RingloomClient;
import io.ringloom.service.TopicConfig;

/**
 * SPI implemented by the processor-generated proxy for a {@code @RingloomTopicPublisher} interface.
 *
 * <p>The runtime constructs one proxy per publisher interface during startup, after the low-level
 * {@link RingloomClient} has registered the topic publication. The proxy owns the
 * {@link io.ringloom.service.TopicPublisher} native handle and the per-publisher
 * {@link TopicAckRegistry} used for {@code replicate_once} acknowledgement.
 */
public interface GeneratedTopicPublisherBinding {
    /** Returns the publisher interface type this binding implements. */
    Class<?> publisherType();

    /** Returns the topic name this publisher writes to. */
    String topic();

    /** Returns the optional client alias used to resolve the low-level client, or {@code ""} for default. */
    default String client() {
        return "";
    }

    /** Returns the native topic configuration for registration. */
    TopicConfig topicConfig();

    /**
     * Constructs the publisher proxy.
     *
     * @param runtime      the started runtime
     * @param client       the low-level client that registered the publication
     * @param serializers  the runtime serializer registry
     * @param ackRegistry  the per-publisher ack registry (owned by the proxy)
     * @return the generated publisher proxy
     */
    Object create(
            RingloomRuntime runtime,
            RingloomClient client,
            SerializerRegistry serializers,
            TopicAckRegistry ackRegistry);
}
