// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.config.RingloomServiceRuntimeConfig;
import io.ringloom.framework.config.kv.KvAcquireMode;
import io.ringloom.framework.config.kv.KvRuntimeConfig;
import io.ringloom.framework.config.kv.KvStoreConfigOverride;
import io.ringloom.framework.config.kv.KvStoreRuntimeConfig;
import io.ringloom.framework.config.kv.KvTtlClock;
import io.ringloom.service.KvStoreConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class KvRuntimeConfigTest {
    @Test
    void applicationConfigDefaultsToDisabledKvAndKeepsFiveArgConstructor() {
        RingloomApplicationConfig config =
                new RingloomApplicationConfig(RingloomServiceRuntimeConfig.of("svc"), null, null, Map.of(), null);
        assertThat(config.kv().enabled()).isFalse();
        assertThat(RingloomApplicationConfig.minimal("svc").kv()).isEqualTo(KvRuntimeConfig.disabled());
    }

    @Test
    void storeDefaults() {
        KvStoreRuntimeConfig defaults = KvStoreRuntimeConfig.defaults();
        assertThat(defaults.reader()).isFalse();
        assertThat(defaults.required()).isFalse();
        assertThat(defaults.waitReadyTimeoutMillis()).isEqualTo(5000);
        assertThat(defaults.ttlClock()).isEqualTo(KvTtlClock.PRECISE);
        assertThat(defaults.maxThreadSessions()).isEqualTo(64);
        assertThat(defaults.sessionPoolSize()).isGreaterThanOrEqualTo(4);
        assertThat(defaults.acquire()).isEqualTo(KvAcquireMode.FAIL_IF_HELD);
        assertThat(defaults.maxPendingAcks()).isEqualTo(1024);
        assertThat(defaults.ackTimeoutMillis()).isZero();
        assertThat(defaults.config().isEmpty()).isTrue();
        assertThat(KvRuntimeConfig.enabledDefaults().store("x")).isEqualTo(defaults);
    }

    @Test
    void validatesStoreNamesAndLimits() {
        assertThat(KvRuntimeConfig.isValidStoreName("prices.v1-a_b")).isTrue();
        assertThat(KvRuntimeConfig.isValidStoreName("")).isFalse();
        assertThat(KvRuntimeConfig.isValidStoreName("a/b")).isFalse();
        assertThat(KvRuntimeConfig.isValidStoreName("x".repeat(65))).isFalse();
        assertThatThrownBy(() -> KvRuntimeConfig.enabled(Map.of("bad name", KvStoreRuntimeConfig.defaults())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad name");
        assertThatThrownBy(() -> new KvRuntimeConfig(true, 0, 1000, 1000, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new KvStoreRuntimeConfig(false, false, false, 0, false, null, 1, 0, null, 0, 0, 1, 0, null))
                .hasMessageContaining("sessionPoolSize");
        assertThatThrownBy(() -> new KvStoreConfigOverride(512, -1, -1, -1, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overrideAppliesOnlySetFields() {
        KvStoreConfig base = new KvStoreConfig(1, 64, 1024, 0, 1 << 20, "hourly", 0);
        KvStoreConfig applied = new KvStoreConfigOverride(-1, -1, -1, 4L << 30, "").applyTo(base);
        assertThat(applied).isEqualTo(new KvStoreConfig(1, 64, 1024, 0, 4L << 30, "hourly", 0));
        assertThat(KvStoreConfigOverride.none().applyTo(base)).isSameAs(base);
    }
}
