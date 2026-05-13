// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.ioc.avaje;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.avaje.inject.BeanScope;
import io.avaje.inject.spi.Builder;
import io.ringloom.framework.RingloomApplicationRunner;
import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.framework.generated.GeneratedClientBinding;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.framework.generated.GeneratedRingloomApplication;
import io.ringloom.framework.metrics.RingloomMetrics;
import io.ringloom.framework.metrics.UnavailableRingloomMetrics;
import io.ringloom.framework.request.RequestResponseRegistry;
import io.ringloom.framework.serialization.EncodeContext;
import io.ringloom.framework.serialization.MessageEncoder;
import io.ringloom.framework.serialization.SerializerModule;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.framework.serialization.WritableMessageBuffer;
import io.ringloom.service.RingloomMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RingloomAvajeModuleTest {

    @Test
    void lazyBeanScopeRegistersCoreRingloomBeansWithoutNativeStartup() {
        // Given
        RingloomApplicationConfig config = RingloomApplicationConfig.minimal("orders");
        GeneratedRingloomApplication generated = generatedApplication("orders", List.of());

        // When
        try (BeanScope scope = BeanScope.builder()
                .bean(RingloomAvajeConfig.class, RingloomAvajeConfig.lazy())
                .bean(RingloomApplicationConfig.class, config)
                .bean(GeneratedRingloomApplication.class, generated)
                .modules(new RingloomAvajeModule())
                .build()) {
            // Then
            assertThat(scope.get(RingloomAvajeConfig.class)).isEqualTo(RingloomAvajeConfig.lazy());
            assertThat(scope.get(RingloomApplicationConfig.class)).isSameAs(config);
            assertThat(scope.get(GeneratedRingloomApplication.class)).isSameAs(generated);
            assertThat(scope.get(GeneratedMessageDispatcher.class)).isNotNull();
            assertThat(scope.get(SerializerRegistry.class).encoder("missing", String.class))
                    .isNull();
            assertThat(scope.get(RingloomMetrics.class)).isSameAs(UnavailableRingloomMetrics.INSTANCE);
            assertThat(scope.get(RingloomRuntime.class))
                    .isSameAs(scope.get(RingloomApplicationRunner.class).runtime());
            assertThat(scope.get(RequestResponseRegistry.class))
                    .isSameAs(scope.get(RingloomRuntime.class).requestResponseRegistry());
        }
    }

    @Test
    void buildsSerializerRegistryFromSerializerModuleBeans() {
        // Given
        RingloomApplicationConfig config = RingloomApplicationConfig.minimal("orders");
        GeneratedRingloomApplication generated = generatedApplication("orders", List.of());
        MessageEncoder<String> encoder = new TestEncoder();
        SerializerModule module = new SerializerModule() {
            @Override
            public void register(SerializerRegistry.Builder builder) {
                builder.encoder("text", String.class, encoder);
            }
        };

        // When
        try (BeanScope scope = BeanScope.builder()
                .bean(RingloomAvajeConfig.class, RingloomAvajeConfig.lazy())
                .bean(RingloomApplicationConfig.class, config)
                .bean(GeneratedRingloomApplication.class, generated)
                .bean(SerializerModule.class, module)
                .modules(new RingloomAvajeModule())
                .build()) {
            // Then
            SerializerRegistry registry = scope.get(SerializerRegistry.class);
            assertThat(registry.encoder("text", String.class)).isSameAs(encoder);
        }
    }

    @Test
    void rejectsAmbiguousConfigBeanAndConfigPath() {
        // Given
        RingloomApplicationConfig config = RingloomApplicationConfig.minimal("orders");
        GeneratedRingloomApplication generated = generatedApplication("orders", List.of());
        RingloomAvajeConfig avajeConfig = new RingloomAvajeConfig(false, false, false, "ringloom.yaml");

        // When / Then
        assertThatThrownBy(() -> BeanScope.builder()
                        .bean(RingloomAvajeConfig.class, avajeConfig)
                        .bean(RingloomApplicationConfig.class, config)
                        .bean(GeneratedRingloomApplication.class, generated)
                        .modules(new RingloomAvajeModule())
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RingLoom config supplied by both bean and config path");
    }

    @Test
    void manualStartRequiresExplicitDependencies() {
        // Given
        RingloomAvajeModule module = new RingloomAvajeModule();

        // When / Then
        assertThatThrownBy(module::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("manual start requires explicit config, generated application, and serializers");
    }

    @Test
    void registersGeneratedClientBeansByInterfaceType() {
        // Given
        RingloomAvajeModule module = new RingloomAvajeModule(RingloomAvajeConfig.lazy());
        Builder builder = mock(Builder.class);
        RingloomRuntime runtime = mock(RingloomRuntime.class);
        TestClient client = mock(TestClient.class);
        GeneratedClientBinding<TestClient> binding = new GeneratedClientBinding<>() {
            @Override
            public Class<TestClient> clientType() {
                return TestClient.class;
            }

            @Override
            public String targetServiceName() {
                return "pricing";
            }

            @Override
            public TestClient create(
                    RingloomRuntime runtime,
                    io.ringloom.service.RingloomClient lowLevelClient,
                    SerializerRegistry serializers) {
                return client;
            }
        };
        GeneratedRingloomApplication generated = generatedApplication("orders", List.of(binding));
        when(builder.contains(TestClient.class)).thenReturn(false);
        when(runtime.generatedClient(TestClient.class)).thenReturn(client);

        // When
        module.registerGeneratedClients(builder, generated, runtime);

        // Then
        verify(builder).withBean(eq(TestClient.class), same(client));
    }

    private static GeneratedRingloomApplication generatedApplication(
            String serviceName, List<GeneratedClientBinding<?>> clients) {
        GeneratedMessageDispatcher dispatcher = new GeneratedMessageDispatcher() {
            @Override
            public int onMessage(RingloomMessage message, MessageContext context) {
                return 0;
            }
        };
        return new GeneratedRingloomApplication() {
            @Override
            public String serviceName() {
                return serviceName;
            }

            @Override
            public List<GeneratedClientBinding<?>> clients() {
                return clients;
            }

            @Override
            public GeneratedMessageDispatcher dispatcher() {
                return dispatcher;
            }
        };
    }

    private interface TestClient {}

    private static final class TestEncoder implements MessageEncoder<String> {

        @Override
        public int templateId() {
            return 1;
        }

        @Override
        public int encodedLength(String value, EncodeContext context) {
            return value.length();
        }

        @Override
        public int encode(String value, WritableMessageBuffer target, EncodeContext context) {
            return value.length();
        }
    }
}
