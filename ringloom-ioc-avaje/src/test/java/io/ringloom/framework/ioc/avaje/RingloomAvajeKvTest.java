// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.ioc.avaje;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.avaje.inject.BeanScope;
import io.avaje.inject.spi.Builder;
import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.config.RingloomServiceRuntimeConfig;
import io.ringloom.framework.config.kv.KvRuntimeConfig;
import io.ringloom.framework.generated.GeneratedClientBinding;
import io.ringloom.framework.generated.GeneratedKvStoreBinding;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.framework.generated.GeneratedRingloomApplication;
import io.ringloom.framework.kv.KvReaderOperations;
import io.ringloom.framework.kv.KvRuntime;
import io.ringloom.framework.kv.KvStoreStatus;
import io.ringloom.framework.kv.KvWriterState;
import io.ringloom.framework.kv.KvWriterStateEvent;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.framework.status.RingloomHandlerStatus;
import io.ringloom.service.KvStoreConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class RingloomAvajeKvTest {

    @Test
    void lazyRegistrationResolvesTheGeneratedStoreFromTheRuntimeOnUse() {
        // Given
        RingloomAvajeModule module = new RingloomAvajeModule(RingloomAvajeConfig.lazy());
        Builder builder = mock(Builder.class);
        AtomicReference<RingloomRuntime> runtimeReference = new AtomicReference<>();
        GeneratedRingloomApplication generated = new KvApplication("kv-svc", Map.of());
        when(builder.contains(PriceStore.class)).thenReturn(false);

        // When
        module.registerLazyGeneratedKvStores(builder, generated, runtimeReference);

        // Then
        ArgumentCaptor<PriceStore> bean = ArgumentCaptor.forClass(PriceStore.class);
        verify(builder).withBean(eq(PriceStore.class), bean.capture());
        PriceStore lazy = bean.getValue();
        assertThat(lazy).hasToString("LazyRingloomClient[" + PriceStore.class.getName() + "]");
        assertThatThrownBy(lazy::isReady)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RingLoom runtime is not available yet for " + PriceStore.class.getName());

        RingloomRuntime runtime = mock(RingloomRuntime.class);
        PriceStore started = mock(PriceStore.class);
        when(started.isReady()).thenReturn(true);
        when(started.storeName()).thenReturn("prices");
        when(runtime.generatedClient(PriceStore.class)).thenReturn(started);
        runtimeReference.set(runtime);
        assertThat(lazy.isReady()).isTrue();
        assertThat(lazy.storeName()).isEqualTo("prices");
    }

    @Test
    void lazyScopeBuildsWithKvStoresDeclared() {
        // Given
        GeneratedRingloomApplication generated = new KvApplication("kv-svc", Map.of());

        // When
        try (BeanScope scope = lazyScope(generated, true)) {
            // Then: no native startup; store proxies need autoStart (registerGeneratedClients).
            assertThat(scope.contains(PriceStore.class)).isFalse();
            assertThat(scope.get(RingloomRuntime.class)).isNotNull();
        }
    }

    @Test
    void registersKvRuntimeBeanOnlyWhenKvIsEnabled() {
        // Given
        GeneratedRingloomApplication generated = new KvApplication("kv-svc", Map.of());

        // When / Then
        try (BeanScope scope = lazyScope(generated, true)) {
            assertThat(scope.contains(KvRuntime.class)).isTrue();
            assertThatThrownBy(() -> scope.get(KvRuntime.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KvRuntime is available after the RingLoom runtime has started");
        }
        try (BeanScope scope = lazyScope(new KvApplication("kv-svc", Map.of(), List.of()), false)) {
            assertThat(scope.contains(KvRuntime.class)).isFalse();
        }
    }

    @Test
    void eagerRegistrationUsesTheStartedRuntimeProxy() {
        // Given
        RingloomAvajeModule module = new RingloomAvajeModule(RingloomAvajeConfig.lazy());
        Builder builder = mock(Builder.class);
        RingloomRuntime runtime = mock(RingloomRuntime.class);
        PriceStore store = mock(PriceStore.class);
        GeneratedRingloomApplication generated = new KvApplication("kv-svc", Map.of());
        when(builder.contains(PriceStore.class)).thenReturn(false);
        when(runtime.generatedClient(PriceStore.class)).thenReturn(store);

        // When
        module.registerGeneratedKvStores(builder, generated, runtime);

        // Then
        verify(builder).withBean(eq(PriceStore.class), same(store));
    }

    @Test
    void eagerRegistrationKeepsAnExistingBean() {
        // Given
        RingloomAvajeModule module = new RingloomAvajeModule(RingloomAvajeConfig.lazy());
        Builder builder = mock(Builder.class);
        RingloomRuntime runtime = mock(RingloomRuntime.class);
        when(builder.contains(PriceStore.class)).thenReturn(true);

        // When
        module.registerGeneratedKvStores(builder, new KvApplication("kv-svc", Map.of()), runtime);

        // Then
        verify(builder, never()).withBean(eq(PriceStore.class), any());
    }

    @Test
    void writerStateHandlerComponentIsTheAvajeBean() {
        // Given
        PriceStateHandler handler = new PriceStateHandler();
        RingloomApplicationConfig config = kvConfig(true);

        AtomicReference<GeneratedRingloomApplication> wiredSink = new AtomicReference<>();

        // When
        try (BeanScope scope = BeanScope.builder()
                .bean(RingloomAvajeConfig.class, RingloomAvajeConfig.lazy())
                .bean(RingloomApplicationConfig.class, config)
                .bean(GeneratedRingloomApplication.class, new KvApplication("kv-svc", Map.of(), wiredSink))
                .bean(PriceStateHandler.class, handler)
                .modules(new RingloomAvajeModule())
                .build()) {
            // The runtime uses the application returned by withComponents(...).
            GeneratedRingloomApplication wired = wiredSink.get();
            assertThat(wired).isNotNull();
            KvWriterStateEvent event = new KvWriterStateEvent("prices", KvWriterState.GRANTED, null, 3);
            wired.dispatchKvWriterState(event);

            // Then
            assertThat(handler.events).containsExactly(event);
        }
    }

    private static BeanScope lazyScope(GeneratedRingloomApplication generated, boolean kvEnabled) {
        return BeanScope.builder()
                .bean(RingloomAvajeConfig.class, RingloomAvajeConfig.lazy())
                .bean(RingloomApplicationConfig.class, kvConfig(kvEnabled))
                .bean(GeneratedRingloomApplication.class, generated)
                .modules(new RingloomAvajeModule())
                .build();
    }

    private static RingloomApplicationConfig kvConfig(boolean kvEnabled) {
        return new RingloomApplicationConfig(
                RingloomServiceRuntimeConfig.of("kv-svc"),
                null,
                null,
                Map.of(),
                null,
                kvEnabled ? KvRuntimeConfig.enabledDefaults() : KvRuntimeConfig.disabled());
    }

    /** Store interface as declared by an application ({@code @RingloomKvStore}). */
    interface PriceStore extends KvReaderOperations {}

    /** A {@code @RingloomServiceComponent} with a {@code @RingloomKvWriterStateHandler} method. */
    static final class PriceStateHandler {
        final List<KvWriterStateEvent> events = new ArrayList<>();

        void onWriterState(KvWriterStateEvent event) {
            events.add(event);
        }
    }

    /**
     * Mirrors the generated application: KV store binding, component types, {@code withComponents}
     * and writer-state dispatch to the component instance.
     */
    private static final class KvApplication implements GeneratedRingloomApplication {
        private final String serviceName;
        private final Map<Class<?>, ?> components;
        private final List<GeneratedKvStoreBinding> stores;
        private final AtomicReference<GeneratedRingloomApplication> wiredSink;

        KvApplication(String serviceName, Map<Class<?>, ?> components) {
            this(serviceName, components, List.of(new PriceStoreBinding()), new AtomicReference<>());
        }

        KvApplication(
                String serviceName, Map<Class<?>, ?> components, AtomicReference<GeneratedRingloomApplication> sink) {
            this(serviceName, components, List.of(new PriceStoreBinding()), sink);
        }

        KvApplication(String serviceName, Map<Class<?>, ?> components, List<GeneratedKvStoreBinding> stores) {
            this(serviceName, components, stores, new AtomicReference<>());
        }

        KvApplication(
                String serviceName,
                Map<Class<?>, ?> components,
                List<GeneratedKvStoreBinding> stores,
                AtomicReference<GeneratedRingloomApplication> wiredSink) {
            this.serviceName = serviceName;
            this.components = components;
            this.stores = stores;
            this.wiredSink = wiredSink;
        }

        @Override
        public String serviceName() {
            return serviceName;
        }

        @Override
        public List<GeneratedClientBinding<?>> clients() {
            return List.of();
        }

        @Override
        public GeneratedMessageDispatcher dispatcher() {
            return (message, context) -> RingloomHandlerStatus.OK;
        }

        @Override
        public List<GeneratedKvStoreBinding> kvStores() {
            return stores;
        }

        @Override
        public List<Class<?>> componentTypes() {
            return List.of(PriceStateHandler.class);
        }

        @Override
        public GeneratedRingloomApplication withComponents(Map<Class<?>, ?> components) {
            KvApplication wired = new KvApplication(serviceName, components, stores, wiredSink);
            wiredSink.set(wired);
            return wired;
        }

        @Override
        public void dispatchKvWriterState(KvWriterStateEvent event) {
            if (event.store().equals("prices")) {
                Object component = components.get(PriceStateHandler.class);
                if (component == null) {
                    throw new IllegalStateException("writer state handler component not wired");
                }
                ((PriceStateHandler) component).onWriterState(event);
            }
        }
    }

    private static final class PriceStoreBinding implements GeneratedKvStoreBinding {
        @Override
        public Class<?> storeType() {
            return PriceStore.class;
        }

        @Override
        public String store() {
            return "prices";
        }

        @Override
        public boolean reads() {
            return true;
        }

        @Override
        public boolean writes() {
            return false;
        }

        @Override
        public KvStoreConfig storeConfig() {
            return null;
        }

        @Override
        public int keyLengthHint() {
            return 8;
        }

        @Override
        public Object create(RingloomRuntime runtime, SerializerRegistry serializers) {
            return new PriceStore() {
                @Override
                public String storeName() {
                    return "prices";
                }

                @Override
                public boolean isReady() {
                    return false;
                }

                @Override
                public KvStoreStatus status() {
                    return runtime.kv().status("prices");
                }
            };
        }
    }
}
