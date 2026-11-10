// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.marketdatakv.it;

import io.ringloom.framework.RingloomRuntime;
import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.config.RingloomRuntimeConfig;
import io.ringloom.framework.config.RingloomSerializerConfig;
import io.ringloom.framework.config.RingloomServiceRuntimeConfig;
import io.ringloom.framework.config.RuntimeMode;
import io.ringloom.framework.config.kv.KvAcquireMode;
import io.ringloom.framework.config.kv.KvRuntimeConfig;
import io.ringloom.framework.config.kv.KvStoreConfigOverride;
import io.ringloom.framework.config.kv.KvStoreRuntimeConfig;
import io.ringloom.framework.config.kv.KvTtlClock;
import io.ringloom.framework.generated.GeneratedRingloomApplication;
import io.ringloom.framework.metrics.UnavailableRingloomMetrics;
import io.ringloom.framework.serialization.SerializerRegistry;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.LoggerFactory;

/** Builds runtimes of the generated {@link KvItApp} against a {@link KvTestBroker}. */
final class KvItRuntimes {
    static final Path REPO_ROOT = Path.of(System.getProperty("ringloom.repoRoot", "/home/dragan/code/ringloom"));

    private KvItRuntimes() {}

    /** A runtime with service name {@code serviceName} running the generated {@code kv-it} application. */
    static RingloomRuntime runtime(
            KvTestBroker broker,
            Path workspace,
            String serviceName,
            RuntimeMode mode,
            KvAcquireMode acquire,
            ItWriterStates states) {
        String storage = broker.storagePath() != null
                ? broker.storagePath()
                : workspace.resolve("storage").toString();
        KvStoreRuntimeConfig store = storeConfig(acquire);
        RingloomApplicationConfig config = new RingloomApplicationConfig(
                new RingloomServiceRuntimeConfig(
                        serviceName, storage, broker.group(), broker.nodeId(), false, 0, 0, 0, false),
                new RingloomRuntimeConfig(mode, null, null, null, null, null, false, null),
                new RingloomSerializerConfig("sbe", Map.of()),
                Map.of(),
                null,
                new KvRuntimeConfig(true, 100, 100, 1000, Map.of(KvItApp.PRICES, store, KvItApp.SYMBOLS, store)));
        GeneratedRingloomApplication generated = new KvItApp_RingloomApplication()
                .withComponents(Map.of(ItWriterStates.class, states == null ? new ItWriterStates() : states));
        SerializerRegistry.Builder serializers = SerializerRegistry.builder();
        generated.registerSerializers(serializers, config.serializers());
        return new RingloomRuntime(
                config,
                generated,
                serializers.build(),
                UnavailableRingloomMetrics.INSTANCE,
                LoggerFactory.getLogger("kv-it." + serviceName));
    }

    private static KvStoreRuntimeConfig storeConfig(KvAcquireMode acquire) {
        return new KvStoreRuntimeConfig(
                false,
                false,
                false,
                5000,
                false,
                KvTtlClock.PRECISE,
                64,
                4,
                acquire,
                5000,
                0,
                4096,
                0,
                KvStoreConfigOverride.none());
    }

    static void pollUntil(RingloomRuntime runtime, BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeoutMillis + " ms");
            }
            runtime.pollControl();
            Thread.sleep(5);
        }
    }
}
