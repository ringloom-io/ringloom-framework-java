// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.ioc.avaje;

import io.ringloom.framework.RingloomApplication;
import io.ringloom.framework.RingloomBootstrap;
import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.generated.GeneratedRingloomApplication;
import io.ringloom.framework.serialization.SerializerRegistry;
import java.util.Objects;

public final class RingloomAvajeModule {
    private final RingloomApplicationConfig config;
    private final GeneratedRingloomApplication generatedApplication;
    private final SerializerRegistry serializers;

    public RingloomAvajeModule(
        RingloomApplicationConfig config,
        GeneratedRingloomApplication generatedApplication,
        SerializerRegistry serializers
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.generatedApplication = Objects.requireNonNull(generatedApplication, "generatedApplication");
        this.serializers = Objects.requireNonNull(serializers, "serializers");
    }

    public RingloomApplication start() {
        return RingloomBootstrap.fromConfig(config)
            .generatedApplication(generatedApplication)
            .serializerRegistry(serializers)
            .start();
    }
}
