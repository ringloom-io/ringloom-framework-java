// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.generated;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.service.RingloomClient;

public interface GeneratedClientBinding<T> {
    Class<T> clientType();

    String targetServiceName();

    T create(RingloomRuntime runtime, RingloomClient lowLevelClient, SerializerRegistry serializers);
}
