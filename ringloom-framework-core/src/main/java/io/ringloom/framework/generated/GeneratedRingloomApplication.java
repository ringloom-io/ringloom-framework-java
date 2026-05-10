// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.generated;

import io.ringloom.framework.RingloomRuntime;
import java.util.List;
import java.util.Map;

public interface GeneratedRingloomApplication {
    String serviceName();

    List<GeneratedClientBinding<?>> clients();

    GeneratedMessageDispatcher dispatcher();

    default Map<Integer, GeneratedPartitionKeyExtractor> partitionKeyExtractors() {
        return Map.of();
    }

    default boolean requiresCorrelationAwareSends() {
        return false;
    }

    default void onRuntimeStarted(RingloomRuntime runtime) {
    }

    default void onRuntimeStopping(RingloomRuntime runtime) {
    }
}
