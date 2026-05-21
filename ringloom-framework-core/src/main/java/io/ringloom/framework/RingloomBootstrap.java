// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework;

import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.config.RingloomConfigLoader;
import io.ringloom.framework.generated.GeneratedRingloomApplication;
import io.ringloom.framework.generated.GeneratedRingloomApplicationProvider;
import io.ringloom.framework.metrics.RingloomMetrics;
import io.ringloom.framework.metrics.UnavailableRingloomMetrics;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.framework.tracing.NoopTraceAdapter;
import io.ringloom.framework.tracing.TraceAdapter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstraps a RingLoom application from configuration and generated metadata.
 */
public final class RingloomBootstrap {

    private final RingloomApplicationConfig config;
    private GeneratedRingloomApplication generatedApplication;
    private SerializerRegistry serializers = SerializerRegistry.EMPTY;
    private RingloomMetrics metrics = UnavailableRingloomMetrics.INSTANCE;
    private TraceAdapter traceAdapter = NoopTraceAdapter.INSTANCE;
    private Logger logger = LoggerFactory.getLogger(RingloomRuntime.class);

    private RingloomBootstrap(RingloomApplicationConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Creates a bootstrap instance by loading a configuration file through the registered
     * {@link RingloomConfigLoader} providers.
     *
     * @param path the configuration path to load
     * @return a bootstrap configured from the supplied source
     */
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

    /**
     * Creates a bootstrap instance from an already constructed application configuration.
     *
     * @param config the application configuration
     * @return a bootstrap for the configuration
     */
    public static RingloomBootstrap fromConfig(RingloomApplicationConfig config) {
        return new RingloomBootstrap(config);
    }

    /**
     * Returns a builder for constructing a bootstrap explicitly.
     *
     * @return the bootstrap builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Overrides the generated application metadata that would otherwise be discovered via service
     * loading.
     *
     * @param generated the generated application metadata
     * @return this bootstrap
     */
    public RingloomBootstrap generatedApplication(GeneratedRingloomApplication generated) {
        this.generatedApplication = Objects.requireNonNull(generated, "generated");
        return this;
    }

    /**
     * Supplies the serializer registry available to generated clients and handlers.
     *
     * @param serializers the serializer registry
     * @return this bootstrap
     */
    public RingloomBootstrap serializerRegistry(SerializerRegistry serializers) {
        this.serializers = Objects.requireNonNull(serializers, "serializers");
        return this;
    }

    /**
     * Supplies the metrics facade exposed by the runtime.
     *
     * @param metrics the metrics facade
     * @return this bootstrap
     */
    public RingloomBootstrap metrics(RingloomMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        return this;
    }

    /**
     * Supplies the tracing adapter used by generated clients and handlers.
     *
     * @param traceAdapter the tracing adapter
     * @return this bootstrap
     */
    public RingloomBootstrap traceAdapter(TraceAdapter traceAdapter) {
        this.traceAdapter = Objects.requireNonNull(traceAdapter, "traceAdapter");
        return this;
    }

    /**
     * Overrides the logger used by runtime components created through this bootstrap.
     *
     * @param logger the logger to use
     * @return this bootstrap
     */
    public RingloomBootstrap logger(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        return this;
    }

    /**
     * Starts the native service, generated clients, and Java event loops.
     *
     * @return a running application runner
     */
    public RingloomApplicationRunner start() {
        GeneratedRingloomApplication generated = generatedApplication == null
                ? discoverGeneratedApplication(config.service().name())
                : generatedApplication;
        if (!generated.serviceName().equals(config.service().name())) {
            throw new IllegalArgumentException("generated service " + generated.serviceName()
                    + " does not match config service "
                    + config.service().name());
        }
        SerializerRegistry resolvedSerializers = resolveSerializers(generated);
        RingloomRuntime runtime =
                new RingloomRuntime(config, generated, resolvedSerializers, metrics, traceAdapter, logger);
        runtime.start();
        runtime.startEventLoops(Thread.ofPlatform().name("ringloom-java-", 0).factory());
        return new RingloomApplicationRunner(runtime, config.runtime().shutdownHook(), generated.serviceName());
    }

    private SerializerRegistry resolveSerializers(GeneratedRingloomApplication generated) {
        SerializerRegistry.Builder builder = SerializerRegistry.builder();
        generated.registerSerializers(builder, config.serializers());
        serializers.registerInto(builder);
        return builder.build();
    }

    private static GeneratedRingloomApplication discoverGeneratedApplication(String serviceName) {
        List<GeneratedRingloomApplication> matches = new ArrayList<>();
        for (GeneratedRingloomApplicationProvider provider :
                ServiceLoader.load(GeneratedRingloomApplicationProvider.class)) {
            GeneratedRingloomApplication application = provider.application();
            if (application.serviceName().equals(serviceName)) {
                matches.add(application);
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalStateException("no generated RingLoom application found for service " + serviceName);
        }
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "multiple generated RingLoom applications found for service " + serviceName);
        }
        return matches.getFirst();
    }

    /**
     * Builder for {@link RingloomBootstrap}.
     */
    public static final class Builder {

        private RingloomApplicationConfig config;

        private Builder() {}

        /**
         * Sets the application configuration used by the bootstrap.
         *
         * @param config the application configuration
         * @return this builder
         */
        public Builder config(RingloomApplicationConfig config) {
            this.config = Objects.requireNonNull(config, "config");
            return this;
        }

        /**
         * Builds the bootstrap.
         *
         * @return the configured bootstrap
         */
        public RingloomBootstrap build() {
            if (config == null) {
                throw new IllegalStateException("config is required");
            }
            return new RingloomBootstrap(config);
        }
    }
}
