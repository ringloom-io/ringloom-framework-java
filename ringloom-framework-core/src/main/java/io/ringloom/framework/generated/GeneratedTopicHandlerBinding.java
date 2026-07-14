// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.generated;

import io.ringloom.service.TopicStart;

/**
 * SPI implemented by the processor-generated metadata describing a {@code @RingloomTopicHandler}.
 *
 * <p>The runtime uses these bindings to subscribe to each topic and wire the partition-key extractor
 * for the handler. The serializer and partition-key geometry are generated-authoritative; the runtime
 * may override {@link #start()} from YAML.
 */
public interface GeneratedTopicHandlerBinding {
    /** Returns the topic name this handler subscribes to. */
    String topic();

    /** Returns the subscription starting position. */
    TopicStart start();

    /** Returns the serializer name used to decode the handler payload. */
    String serializer();

    /** Returns the partition-key extractor name, or {@code null}/{@code ""} for keyless routing. */
    String partitionKey();
}
