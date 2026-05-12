// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

/**
 * Module that registers one or more serializers with a {@link SerializerRegistry.Builder}.
 */
public interface SerializerModule {
    void register(SerializerRegistry.Builder builder);
}
