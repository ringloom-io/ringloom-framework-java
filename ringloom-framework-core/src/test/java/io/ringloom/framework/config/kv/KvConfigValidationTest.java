// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config.kv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.config.RingloomServiceRuntimeConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class KvConfigValidationTest {

    @Test
    void runtimeConfigRejectsNonPositiveIntervals() {
        assertThatThrownBy(() -> new KvRuntimeConfig(true, 0, 1, 1, Map.of()))
                .hasMessage("kv.statusIntervalMillis must be > 0");
        assertThatThrownBy(() -> new KvRuntimeConfig(true, 1, 0, 1, Map.of()))
                .hasMessage("kv.openRetryMillis must be > 0");
        assertThatThrownBy(() -> new KvRuntimeConfig(true, 1, 1, -1, Map.of()))
                .hasMessage("kv.closeTimeoutMillis must be > 0");
    }

    @Test
    void runtimeConfigRejectsInvalidStoreNames() {
        assertThatThrownBy(() -> KvRuntimeConfig.enabled(Map.of("x".repeat(65), KvStoreRuntimeConfig.defaults())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid store name");
        assertThatThrownBy(() -> KvRuntimeConfig.enabled(Map.of("prices:eu", KvStoreRuntimeConfig.defaults())))
                .hasMessage("kv.stores.prices:eu: invalid store name (1..64 bytes of [A-Za-z0-9._-])");
    }

    @Test
    void storeConfigValidatesBounds() {
        assertThatThrownBy(() -> store(-1, 64, 4, 5000, 0, 1024, 0)).hasMessageContaining("waitReadyTimeoutMillis");
        assertThatThrownBy(() -> store(1L << 32, 64, 4, 5000, 0, 1024, 0))
                .hasMessageContaining("waitReadyTimeoutMillis");
        assertThatThrownBy(() -> store(0, -1, 4, 5000, 0, 1024, 0)).hasMessageContaining("maxThreadSessions");
        assertThatThrownBy(() -> store(0, 64, 0, 5000, 0, 1024, 0)).hasMessageContaining("sessionPoolSize");
        assertThatThrownBy(() -> store(0, 64, 4, -1, 0, 1024, 0)).hasMessageContaining("openTimeoutMillis");
        assertThatThrownBy(() -> store(0, 64, 4, 5000, -1, 1024, 0)).hasMessageContaining("maxFrameLength");
        assertThatThrownBy(() -> store(0, 64, 4, 5000, 0, 0, 0)).hasMessageContaining("maxPendingAcks");
        assertThatThrownBy(() -> store(0, 64, 4, 5000, 0, 1024, -1)).hasMessageContaining("ackTimeoutMillis");
        // maxThreadSessions = 0 is allowed: every read borrows from the pool.
        assertThat(store(0, 0, 1, 0, 0, 1, 0).maxThreadSessions()).isZero();
    }

    @Test
    void storeConfigDefaultsNullEnumsAndOverride() {
        KvStoreRuntimeConfig config =
                new KvStoreRuntimeConfig(true, false, false, 0, false, null, 1, 1, null, 0, 0, 1, 0, null);
        assertThat(config.ttlClock()).isEqualTo(KvTtlClock.PRECISE);
        assertThat(config.acquire()).isEqualTo(KvAcquireMode.FAIL_IF_HELD);
        assertThat(config.config()).isEqualTo(KvStoreConfigOverride.none());
    }

    @Test
    void overrideValidatesFields() {
        assertThatThrownBy(() -> new KvStoreConfigOverride(0, -1, -1, -1, "")).hasMessageContaining("maxKeyLength");
        assertThatThrownBy(() -> new KvStoreConfigOverride(-1, -2, -1, -1, "")).hasMessageContaining("maxValueLength");
        assertThatThrownBy(() -> new KvStoreConfigOverride(-1, 1L << 32, -1, -1, ""))
                .hasMessageContaining("maxValueLength");
        assertThatThrownBy(() -> new KvStoreConfigOverride(-1, -1, -2, -1, ""))
                .hasMessageContaining("defaultTtlMillis");
        assertThatThrownBy(() -> new KvStoreConfigOverride(-1, -1, -1, -2, "")).hasMessageContaining("mapSizeHint");
        assertThat(new KvStoreConfigOverride(511, 0, 0, 0, null).rollScheme()).isEmpty();
    }

    @Test
    void applicationConfigCarriesKv() {
        KvRuntimeConfig kv = KvRuntimeConfig.enabled(Map.of("prices", KvStoreRuntimeConfig.defaults()));
        RingloomApplicationConfig config =
                new RingloomApplicationConfig(RingloomServiceRuntimeConfig.of("svc"), null, null, Map.of(), null, kv);
        assertThat(config.kv()).isSameAs(kv);
        assertThat(new RingloomApplicationConfig(RingloomServiceRuntimeConfig.of("svc"), null, null, null, null, null)
                        .kv())
                .isEqualTo(KvRuntimeConfig.disabled());
    }

    private static KvStoreRuntimeConfig store(
            long waitReady,
            int maxThreadSessions,
            int sessionPoolSize,
            long openTimeout,
            long maxFrameLength,
            int maxPendingAcks,
            long ackTimeout) {
        return new KvStoreRuntimeConfig(
                false,
                false,
                false,
                waitReady,
                false,
                KvTtlClock.PRECISE,
                maxThreadSessions,
                sessionPoolSize,
                KvAcquireMode.FAIL_IF_HELD,
                openTimeout,
                maxFrameLength,
                maxPendingAcks,
                ackTimeout,
                KvStoreConfigOverride.none());
    }
}
