// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.ioc.avaje;

import io.avaje.inject.spi.AvajeModule;
import io.avaje.inject.spi.Builder;
import io.ringloom.framework.RingloomApplicationRunner;
import io.ringloom.framework.RingloomBootstrap;
import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.config.RingloomConfigLoader;
import io.ringloom.framework.dispatch.MessageExecutionPolicy;
import io.ringloom.framework.generated.GeneratedClientBinding;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.framework.generated.GeneratedRingloomApplication;
import io.ringloom.framework.generated.GeneratedRingloomApplicationProvider;
import io.ringloom.framework.metrics.RingloomMetrics;
import io.ringloom.framework.metrics.UnavailableRingloomMetrics;
import io.ringloom.framework.request.RequestResponseRegistry;
import io.ringloom.framework.serialization.SerializerModule;
import io.ringloom.framework.serialization.SerializerRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Avaje custom module that registers RingLoom runtime, generated metadata, serializers, metrics,
 * dispatcher, request registry, and generated clients as Avaje beans.
 */
public final class RingloomAvajeModule implements AvajeModule.Custom {

    private final RingloomAvajeConfig moduleConfig;
    private final RingloomApplicationConfig suppliedConfig;
    private final GeneratedRingloomApplication suppliedGeneratedApplication;
    private final SerializerRegistry suppliedSerializers;
    private final RingloomMetrics suppliedMetrics;
    private final Logger suppliedLogger;

    /**
     * Creates an Avaje module that resolves dependencies from the BeanScope builder.
     */
    public RingloomAvajeModule() {
        this(null, null, null, null, null, null);
    }

    /**
     * Creates an Avaje module with explicit adapter configuration.
     *
     * @param moduleConfig the adapter configuration
     */
    public RingloomAvajeModule(RingloomAvajeConfig moduleConfig) {
        this(moduleConfig, null, null, null, null, null);
    }

    /**
     * Creates a manual bootstrap wrapper from explicit dependencies.
     *
     * @param config the RingLoom application config
     * @param generatedApplication the generated application metadata
     * @param serializers the serializer registry
     */
    public RingloomAvajeModule(
            RingloomApplicationConfig config,
            GeneratedRingloomApplication generatedApplication,
            SerializerRegistry serializers) {
        this(RingloomAvajeConfig.defaults(), config, generatedApplication, serializers, null, null);
    }

    private RingloomAvajeModule(
            RingloomAvajeConfig moduleConfig,
            RingloomApplicationConfig suppliedConfig,
            GeneratedRingloomApplication suppliedGeneratedApplication,
            SerializerRegistry suppliedSerializers,
            RingloomMetrics suppliedMetrics,
            Logger suppliedLogger) {
        this.moduleConfig = moduleConfig;
        this.suppliedConfig = suppliedConfig;
        this.suppliedGeneratedApplication = suppliedGeneratedApplication;
        this.suppliedSerializers = suppliedSerializers;
        this.suppliedMetrics = suppliedMetrics;
        this.suppliedLogger = suppliedLogger;
    }

    @Override
    public Class<?>[] classes() {
        return new Class<?>[] {
            RingloomAvajeConfig.class,
            RingloomApplicationConfig.class,
            SerializerRegistry.class,
            GeneratedRingloomApplication.class,
            RingloomRuntime.class,
            RingloomApplicationRunner.class,
            RingloomMetrics.class,
            MessageExecutionPolicy.class,
            RequestResponseRegistry.class,
            GeneratedMessageDispatcher.class,
        };
    }

    @Override
    public void build(Builder builder) {
        Objects.requireNonNull(builder, "builder");
        RingloomAvajeConfig avajeConfig = resolveAdapterConfig(builder);
        registerAbsent(builder, RingloomAvajeConfig.class, avajeConfig);

        RingloomApplicationConfig config = resolveApplicationConfig(builder, avajeConfig);
        registerAbsent(builder, RingloomApplicationConfig.class, config);

        GeneratedRingloomApplication generatedApplication = resolveGeneratedApplication(builder, config);
        registerAbsent(builder, GeneratedRingloomApplication.class, generatedApplication);
        registerAbsent(builder, GeneratedMessageDispatcher.class, generatedApplication.dispatcher());

        SerializerRegistry serializers = resolveSerializers(builder, generatedApplication);
        registerAbsent(builder, SerializerRegistry.class, serializers);

        RingloomMetrics metrics = resolveMetrics(builder);
        registerAbsent(builder, RingloomMetrics.class, metrics);

        RingloomRuntime runtime =
                new RingloomRuntime(config, generatedApplication, serializers, metrics, resolveLogger());
        if (avajeConfig.autoStart()) {
            runtime.start();
            if (avajeConfig.startEventLoops()) {
                runtime.startEventLoops(
                        Thread.ofPlatform().name("ringloom-avaje-", 0).factory());
            }
        }
        registerAbsent(builder, RingloomRuntime.class, runtime);

        RingloomApplicationRunner application = new RingloomApplicationRunner(
                runtime, config.runtime().shutdownHook(), generatedApplication.serviceName());
        registerAbsent(builder, RingloomApplicationRunner.class, application);
        builder.addPreDestroy(application);

        registerAbsent(builder, RequestResponseRegistry.class, runtime.requestResponseRegistry());
        if (avajeConfig.autoStart()) {
            registerAbsent(builder, MessageExecutionPolicy.class, runtime.messageExecutionPolicy());
        }
        if (avajeConfig.registerGeneratedClients()) {
            registerGeneratedClients(builder, generatedApplication, runtime);
        }
    }

    /**
     * Starts a RingLoom application runner using the explicit constructor dependencies.
     *
     * @return the running RingLoom application runner
     */
    public RingloomApplicationRunner start() {
        if (suppliedConfig == null || suppliedGeneratedApplication == null || suppliedSerializers == null) {
            throw new IllegalStateException(
                    "manual start requires explicit config, generated application, and serializers");
        }
        return RingloomBootstrap.fromConfig(suppliedConfig)
                .generatedApplication(suppliedGeneratedApplication)
                .serializerRegistry(suppliedSerializers)
                .start();
    }

    void registerGeneratedClients(
            Builder builder, GeneratedRingloomApplication generatedApplication, RingloomRuntime runtime) {
        for (GeneratedClientBinding<?> binding : generatedApplication.clients()) {
            registerGeneratedClient(builder, binding, runtime);
        }
    }

    private <T> void registerGeneratedClient(
            Builder builder, GeneratedClientBinding<T> binding, RingloomRuntime runtime) {
        Class<T> clientType = binding.clientType();
        if (!builder.contains(clientType)) {
            builder.withBean(clientType, runtime.generatedClient(clientType));
        }
    }

    private RingloomAvajeConfig resolveAdapterConfig(Builder builder) {
        return moduleConfig == null
                ? builder.getOptional(RingloomAvajeConfig.class).orElse(RingloomAvajeConfig.defaults())
                : moduleConfig;
    }

    private RingloomApplicationConfig resolveApplicationConfig(Builder builder, RingloomAvajeConfig avajeConfig) {
        Optional<RingloomApplicationConfig> configBean = suppliedConfig == null
                ? builder.getOptional(RingloomApplicationConfig.class)
                : Optional.of(suppliedConfig);
        Optional<String> propertyPath = builder.property().get(RingloomAvajeConfig.CONFIG_PATH_PROPERTY);
        Optional<String> configuredPath = Optional.ofNullable(avajeConfig.configPath());
        if (propertyPath.isPresent() && configuredPath.isPresent()) {
            throw new IllegalStateException(
                    "RingLoom config path supplied by both Avaje property and RingloomAvajeConfig");
        }
        Optional<String> path = configuredPath.or(() -> propertyPath);
        if (configBean.isPresent() && path.isPresent()) {
            throw new IllegalStateException("RingLoom config supplied by both bean and config path");
        }
        return configBean.orElseGet(() -> loadConfig(path.orElseThrow(
                () -> new IllegalStateException("RingLoom application config bean or config path is required"))));
    }

    private GeneratedRingloomApplication resolveGeneratedApplication(
            Builder builder, RingloomApplicationConfig config) {
        return suppliedGeneratedApplication == null
                ? builder.getOptional(GeneratedRingloomApplication.class)
                        .orElseGet(() ->
                                discoverGeneratedApplication(config.service().name()))
                : suppliedGeneratedApplication;
    }

    private SerializerRegistry resolveSerializers(Builder builder, GeneratedRingloomApplication generatedApplication) {
        SerializerRegistry.Builder registryBuilder = SerializerRegistry.builder();
        generatedApplication.registerSerializers(registryBuilder);
        if (suppliedSerializers != null) {
            suppliedSerializers.registerInto(registryBuilder);
            return registryBuilder.build();
        }
        Optional<SerializerRegistry> registry = builder.getOptional(SerializerRegistry.class);
        if (registry.isPresent()) {
            registry.get().registerInto(registryBuilder);
            return registryBuilder.build();
        }
        for (SerializerModule module : builder.list(SerializerModule.class)) {
            registryBuilder.module(module);
        }
        return registryBuilder.build();
    }

    private RingloomMetrics resolveMetrics(Builder builder) {
        if (suppliedMetrics != null) {
            return suppliedMetrics;
        }
        return builder.getOptional(RingloomMetrics.class).orElse(UnavailableRingloomMetrics.INSTANCE);
    }

    private Logger resolveLogger() {
        return suppliedLogger == null ? LoggerFactory.getLogger(RingloomRuntime.class) : suppliedLogger;
    }

    private static RingloomApplicationConfig loadConfig(String path) {
        Path configPath = Path.of(path);
        for (RingloomConfigLoader loader : ServiceLoader.load(RingloomConfigLoader.class)) {
            if (loader.supports(configPath)) {
                try {
                    return loader.load(configPath);
                } catch (IOException ex) {
                    throw new IllegalArgumentException("failed to load RingLoom config " + configPath, ex);
                }
            }
        }
        throw new IllegalStateException("no RingLoom config loader found for " + configPath);
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

    private static <T> void registerAbsent(Builder builder, Class<T> type, T bean) {
        if (!builder.contains(type)) {
            builder.withBean(type, bean);
        }
    }
}
