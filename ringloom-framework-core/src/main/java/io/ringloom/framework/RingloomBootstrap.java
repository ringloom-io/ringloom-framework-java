// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework;

import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.config.RingloomConfigLoader;
import io.ringloom.framework.generated.GeneratedRingloomApplication;
import io.ringloom.framework.generated.GeneratedRingloomApplicationProvider;
import io.ringloom.framework.metrics.RingloomMetrics;
import io.ringloom.framework.metrics.UnavailableRingloomMetrics;
import io.ringloom.framework.serialization.SerializerRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RingloomBootstrap {
    private final RingloomApplicationConfig config;
    private GeneratedRingloomApplication generatedApplication;
    private SerializerRegistry serializers = SerializerRegistry.EMPTY;
    private RingloomMetrics metrics = UnavailableRingloomMetrics.INSTANCE;
    private Logger logger = LoggerFactory.getLogger(RingloomRuntime.class);

    private RingloomBootstrap(RingloomApplicationConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public static RingloomBootstrap fromYaml(Path path) {
        Objects.requireNonNull(path, "path");
        for (RingloomConfigLoader loader : ServiceLoader.load(RingloomConfigLoader.class)) {
            if (loader.supports(path)) {
                try {
                    return fromConfig(loader.load(path));
                } catch (IOException ex) {
                    throw new IllegalArgumentException("failed to load RingLoom YAML config " + path, ex);
                }
            }
        }
        throw new IllegalStateException("no RingLoom YAML config loader found; add ringloom-framework-yaml");
    }

    public static RingloomBootstrap fromConfig(RingloomApplicationConfig config) {
        return new RingloomBootstrap(config);
    }

    public static Builder builder() {
        return new Builder();
    }

    public RingloomBootstrap generatedApplication(GeneratedRingloomApplication generated) {
        this.generatedApplication = Objects.requireNonNull(generated, "generated");
        return this;
    }

    public RingloomBootstrap serializerRegistry(SerializerRegistry serializers) {
        this.serializers = Objects.requireNonNull(serializers, "serializers");
        return this;
    }

    public RingloomBootstrap metrics(RingloomMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        return this;
    }

    public RingloomBootstrap logger(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        return this;
    }

    public RingloomApplication start() {
        GeneratedRingloomApplication generated = generatedApplication == null
            ? discoverGeneratedApplication(config.service().name())
            : generatedApplication;
        if (!generated.serviceName().equals(config.service().name())) {
            throw new IllegalArgumentException(
                "generated service " + generated.serviceName() + " does not match config service " + config.service().name()
            );
        }
        RingloomRuntime runtime = new RingloomRuntime(config, generated, serializers, metrics, logger);
        runtime.start();
        runtime.startEventLoops(Thread.ofPlatform().name("ringloom-java-", 0).factory());
        return new RingloomApplication(runtime);
    }

    private static GeneratedRingloomApplication discoverGeneratedApplication(String serviceName) {
        List<GeneratedRingloomApplication> matches = new ArrayList<>();
        for (GeneratedRingloomApplicationProvider provider : ServiceLoader.load(GeneratedRingloomApplicationProvider.class)) {
            GeneratedRingloomApplication application = provider.application();
            if (application.serviceName().equals(serviceName)) {
                matches.add(application);
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalStateException("no generated RingLoom application found for service " + serviceName);
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("multiple generated RingLoom applications found for service " + serviceName);
        }
        return matches.getFirst();
    }

    public static final class Builder {
        private RingloomApplicationConfig config;

        private Builder() {
        }

        public Builder config(RingloomApplicationConfig config) {
            this.config = Objects.requireNonNull(config, "config");
            return this;
        }

        public RingloomBootstrap build() {
            if (config == null) {
                throw new IllegalStateException("config is required");
            }
            return new RingloomBootstrap(config);
        }
    }
}
