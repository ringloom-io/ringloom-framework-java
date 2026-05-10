// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serialization;

public interface SerializerModule {
    String name();

    void register(SerializerRegistry.Builder builder);
}
