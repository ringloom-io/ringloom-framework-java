// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.generated;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.service.RingloomClient;

/**
 * Describes a generated client binding that can be instantiated by the runtime.
 *
 * @param <T> the generated client interface type
 */
public interface GeneratedClientBinding<T> {
    /**
     * Returns the public client interface implemented by the generated binding.
     *
     * @return the generated client type
     */
    Class<T> clientType();

    /**
     * Returns the RingLoom service name targeted by the generated client.
     *
     * @return the target service name
     */
    String targetServiceName();

    /**
     * Creates the generated client instance for a runtime and low-level client pair.
     *
     * @param runtime the owning runtime
     * @param lowLevelClient the low-level native client used to send messages
     * @param serializers the serializer registry available to the client
     * @return the generated client instance
     */
    T create(RingloomRuntime runtime, RingloomClient lowLevelClient, SerializerRegistry serializers);
}
