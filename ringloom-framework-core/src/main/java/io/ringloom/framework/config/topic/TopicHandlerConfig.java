// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config.topic;

import io.ringloom.service.TopicStart;

/**
 * Runtime override metadata for a single {@code @RingloomTopicHandler}.
 *
 * <p>{@code start} may be {@code null}; the runtime defaults it to {@link TopicStart#EARLIEST} at
 * registration time. {@code serializer} and {@code partitionKey} mirror the annotation values and are
 * reconciled with generated metadata (generated values are authoritative for serializer/partition key
 * geometry; YAML {@code start} overrides the annotation default).
 *
 * @param topic         topic name the handler subscribes to
 * @param start         starting position, or {@code null} to default to {@link TopicStart#EARLIEST}
 * @param serializer    serializer name used to decode the handler payload
 * @param partitionKey  partition-key extractor name, or {@code null}/{@code ""} for keyless routing
 */
public record TopicHandlerConfig(String topic, TopicStart start, String serializer, String partitionKey) {}
