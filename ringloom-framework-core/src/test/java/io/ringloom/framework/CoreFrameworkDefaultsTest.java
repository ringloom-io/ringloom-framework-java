// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.ringloom.framework.config.IdleStrategyKind;
import io.ringloom.framework.config.RingloomSerializerConfig;
import io.ringloom.framework.dispatch.MessageContext;
import io.ringloom.framework.eventloop.IdleStrategies;
import io.ringloom.framework.eventloop.YieldingIdleStrategy;
import io.ringloom.framework.generated.GeneratedMessageDispatcher;
import io.ringloom.framework.generated.GeneratedRingloomApplication;
import io.ringloom.framework.metrics.UnavailableRingloomMetrics;
import io.ringloom.framework.serialization.SerializerRegistry;
import io.ringloom.framework.tracing.ClientTraceContext;
import io.ringloom.framework.tracing.NoopTraceAdapter;
import io.ringloom.service.RingloomMessage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.SleepingIdleStrategy;
import org.junit.jupiter.api.Test;

final class CoreFrameworkDefaultsTest {

    @Test
    void createsBuiltInIdleStrategies() {
        // Given / When / Then
        assertThat(IdleStrategies.create(IdleStrategyKind.BUSY_SPIN)).isInstanceOf(BusySpinIdleStrategy.class);
        assertThat(IdleStrategies.create(IdleStrategyKind.YIELDING)).isInstanceOf(YieldingIdleStrategy.class);
        assertThat(IdleStrategies.create(IdleStrategyKind.SLEEPING)).isInstanceOf(SleepingIdleStrategy.class);
        assertThat(IdleStrategies.create(IdleStrategyKind.BACKOFF)).isInstanceOf(BackoffIdleStrategy.class);
        assertThat(IdleStrategies.create(IdleStrategyKind.NO_OP)).isInstanceOf(NoOpIdleStrategy.class);
    }

    @Test
    void validatesIdleStrategyConstructors() {
        // Given / When / Then
        assertThatThrownBy(() -> new YieldingIdleStrategy(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("spinCount must be non-negative");
    }

    @Test
    void generatedDispatcherDefaultsRouteContextAndRejectPlainCallback() {
        // Given
        MessageContext context = new MessageContext();
        GeneratedMessageDispatcher dispatcher = new GeneratedMessageDispatcher() {
            @Override
            public int onMessage(RingloomMessage message, MessageContext context) {
                return message == null ? 7 : 8;
            }
        };

        // When / Then
        assertThat(dispatcher.onContextMessage(context)).isEqualTo(7);
        assertThatThrownBy(() -> dispatcher.onMessage((RingloomMessage) null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Generated dispatch requires a MessageContext");
    }

    @Test
    void generatedApplicationDefaultsAreNoOps() {
        // Given
        GeneratedMessageDispatcher dispatcher = (message, context) -> 0;
        GeneratedRingloomApplication application = new GeneratedRingloomApplication() {
            @Override
            public String serviceName() {
                return "orders";
            }

            @Override
            public List<io.ringloom.framework.generated.GeneratedClientBinding<?>> clients() {
                return List.of();
            }

            @Override
            public GeneratedMessageDispatcher dispatcher() {
                return dispatcher;
            }
        };

        // When / Then
        assertThat(application.partitionKeyExtractors()).isEmpty();
        assertThat(application.requiresCorrelationAwareSends()).isFalse();
        assertThatCode(() -> application.registerSerializers(SerializerRegistry.builder()))
                .doesNotThrowAnyException();
        assertThatCode(() -> application.registerSerializers(
                        SerializerRegistry.builder(), new RingloomSerializerConfig("fory", java.util.Map.of())))
                .doesNotThrowAnyException();
        assertThatCode(() -> application.initializeSerializers(SerializerRegistry.EMPTY))
                .doesNotThrowAnyException();
        assertThatCode(() -> application.onRuntimeStarted(null)).doesNotThrowAnyException();
        assertThatCode(() -> application.onRuntimeStopping(null)).doesNotThrowAnyException();
    }

    @Test
    void applicationRunnerAwaitShutdownDelegatesToRuntimeShutdown() throws Exception {
        // Given
        RingloomRuntime runtime = mock(RingloomRuntime.class);
        RingloomApplicationRunner application = new RingloomApplicationRunner(runtime, false, "orders");

        // When
        application.awaitShutdown();
        application.close();

        // Then
        verify(runtime).awaitShutdown();
        verify(runtime).close();
    }

    @Test
    void applicationRunnerClosesHookOnce() {
        // Given
        RingloomRuntime runtime = mock(RingloomRuntime.class);
        AtomicInteger closed = new AtomicInteger();
        RingloomApplicationRunner application =
                new RingloomApplicationRunner(runtime, false, "orders", closed::incrementAndGet);

        // When
        application.close();
        application.close();

        // Then
        verify(runtime).close();
        assertThat(closed.get()).isEqualTo(1);
    }

    @Test
    void noOpTraceAdapterReturnsReusableClosableScopes() {
        // Given
        NoopTraceAdapter adapter = NoopTraceAdapter.INSTANCE;
        ClientTraceContext clientContext = new ClientTraceContext("client", "orders", 1, null, 8);
        MessageContext messageContext = new MessageContext();

        // When / Then
        assertThat(adapter.onSendStart(clientContext)).isSameAs(adapter.onSendStart(clientContext));
        assertThat(adapter.onReceiveStart(messageContext)).isSameAs(adapter.onReceiveStart(messageContext));
        assertThatCode(() -> adapter.onSendStart(clientContext).close()).doesNotThrowAnyException();
        assertThatCode(() -> adapter.onSendComplete(clientContext, 0)).doesNotThrowAnyException();
        assertThatCode(() -> adapter.onHandlerComplete(messageContext, 0)).doesNotThrowAnyException();
    }

    @Test
    void unavailableMetricsExposeEmptySamplesAndThrowForNativeReaders() {
        // Given
        UnavailableRingloomMetrics metrics = UnavailableRingloomMetrics.INSTANCE;

        // When / Then
        assertThat(metrics.samples()).isEmpty();
        assertThatThrownBy(() -> metrics.sample("messages"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("RingLoom native metrics reader ABI is not available");
        assertThatThrownBy(() -> metrics.ringStats("control"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("RingLoom native metrics reader ABI is not available");
    }
}
