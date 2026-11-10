// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ringloom.framework.config.RingloomApplicationConfig;
import io.ringloom.framework.config.kv.KvAcquireMode;
import io.ringloom.framework.config.kv.KvRuntimeConfig;
import io.ringloom.framework.config.kv.KvStoreConfigOverride;
import io.ringloom.framework.config.kv.KvStoreRuntimeConfig;
import io.ringloom.framework.config.kv.KvTtlClock;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class YamlKvConfigLoaderTest {
    private static final String HEADER = """
            ringloom:
              service:
                name: kv-svc
            """;

    @TempDir
    Path tempDir;

    @Test
    void parsesKvSection() throws Exception {
        RingloomApplicationConfig config = load("""
                  kv:
                    enabled: true
                    statusIntervalMillis: 500
                    openRetryMillis: 250
                    closeTimeoutMillis: 2000
                    stores:
                      prices:
                        required: true
                        waitReadyTimeoutMillis: 3000
                        prefault: true
                        ttlClock: coarse
                        maxThreadSessions: 32
                        sessionPoolSize: 8
                        acquire: wait
                        openTimeoutMillis: 7000
                        maxFrameLength: 65536
                        maxPendingAcks: 4096
                        ackTimeoutMillis: 2000
                        config:
                          maxKeyLength: 64
                          maxValueLength: 1024
                          defaultTtlMillis: 60000
                          mapSizeHint: 4294967296
                          rollScheme: FAST_HOURLY
                      audit-cache:
                        reader: true
                """);

        KvRuntimeConfig kv = config.kv();
        assertThat(kv.enabled()).isTrue();
        assertThat(kv.statusIntervalMillis()).isEqualTo(500);
        assertThat(kv.openRetryMillis()).isEqualTo(250);
        assertThat(kv.closeTimeoutMillis()).isEqualTo(2000);
        assertThat(kv.stores()).containsOnlyKeys("prices", "audit-cache");

        KvStoreRuntimeConfig prices = kv.stores().get("prices");
        assertThat(prices.reader()).isFalse();
        assertThat(prices.writer()).isFalse();
        assertThat(prices.required()).isTrue();
        assertThat(prices.waitReadyTimeoutMillis()).isEqualTo(3000);
        assertThat(prices.prefault()).isTrue();
        assertThat(prices.ttlClock()).isEqualTo(KvTtlClock.COARSE);
        assertThat(prices.maxThreadSessions()).isEqualTo(32);
        assertThat(prices.sessionPoolSize()).isEqualTo(8);
        assertThat(prices.acquire()).isEqualTo(KvAcquireMode.WAIT);
        assertThat(prices.openTimeoutMillis()).isEqualTo(7000);
        assertThat(prices.maxFrameLength()).isEqualTo(65536);
        assertThat(prices.maxPendingAcks()).isEqualTo(4096);
        assertThat(prices.ackTimeoutMillis()).isEqualTo(2000);
        assertThat(prices.config()).isEqualTo(new KvStoreConfigOverride(64, 1024, 60000, 4294967296L, "FAST_HOURLY"));

        KvStoreRuntimeConfig audit = kv.stores().get("audit-cache");
        assertThat(audit).isEqualTo(KvStoreRuntimeConfig.defaults().withRoles(true, false));
    }

    @Test
    void absentKvSectionDefaultsToDisabled() throws Exception {
        RingloomApplicationConfig config = load("");

        assertThat(config.kv()).isEqualTo(KvRuntimeConfig.disabled());
    }

    @Test
    void kvSectionDefaults() throws Exception {
        RingloomApplicationConfig config = load("""
                  kv:
                    enabled: true
                    stores:
                      prices: {}
                      quotes:
                        config:
                          mapSizeHint: 1048576
                """);

        KvRuntimeConfig kv = config.kv();
        assertThat(kv.enabled()).isTrue();
        assertThat(kv.statusIntervalMillis()).isEqualTo(KvRuntimeConfig.DEFAULT_STATUS_INTERVAL_MILLIS);
        assertThat(kv.openRetryMillis()).isEqualTo(KvRuntimeConfig.DEFAULT_OPEN_RETRY_MILLIS);
        assertThat(kv.closeTimeoutMillis()).isEqualTo(KvRuntimeConfig.DEFAULT_CLOSE_TIMEOUT_MILLIS);
        assertThat(kv.stores().get("prices")).isEqualTo(KvStoreRuntimeConfig.defaults());
        KvStoreConfigOverride quotes = kv.stores().get("quotes").config();
        assertThat(quotes).isEqualTo(new KvStoreConfigOverride(-1, -1, -1, 1048576, ""));
    }

    @Test
    void enabledDefaultsToFalseWhenOmitted() throws Exception {
        RingloomApplicationConfig config = load("""
                  kv:
                    statusIntervalMillis: 200
                """);

        assertThat(config.kv().enabled()).isFalse();
        assertThat(config.kv().statusIntervalMillis()).isEqualTo(200);
    }

    @Test
    void rejectsUnknownKvKey() throws Exception {
        assertLoadFails("""
                  kv:
                    enabled: true
                    bogus: 1
                """, "unknown key ringloom.kv.bogus");
    }

    @Test
    void rejectsUnknownStoreKey() throws Exception {
        assertLoadFails("""
                  kv:
                    enabled: true
                    stores:
                      prices:
                        readers: true
                """, "unknown key ringloom.kv.stores.prices.readers");
    }

    @Test
    void rejectsUnknownStoreConfigKey() throws Exception {
        assertLoadFails("""
                  kv:
                    enabled: true
                    stores:
                      prices:
                        config:
                          mapSize: 10
                """, "unknown key ringloom.kv.stores.prices.config.mapSize");
    }

    @Test
    void rejectsUnknownEnumValuesWithPath() throws Exception {
        assertLoadFails("""
                  kv:
                    stores:
                      prices:
                        ttlClock: PRECISE
                """, "unknown ringloom.kv.stores.prices.ttlClock PRECISE (precise | coarse)");
        assertLoadFails("""
                  kv:
                    stores:
                      prices:
                        acquire: fail_if_held
                """, "unknown ringloom.kv.stores.prices.acquire fail_if_held (failIfHeld | wait)");
    }

    @Test
    void rejectsWrongTypesWithPath() throws Exception {
        assertLoadFails("""
                  kv:
                    enabled: yes please
                """, "ringloom.kv.enabled must be a boolean");
        assertLoadFails("""
                  kv:
                    stores:
                      prices:
                        maxPendingAcks: many
                """, "ringloom.kv.stores.prices.maxPendingAcks must be an integer");
        assertLoadFails("""
                  kv:
                    stores: [prices]
                """, "ringloom.kv.stores must be a map");
    }

    @Test
    void reportsValidationFailuresWithPath() throws Exception {
        assertLoadFails("""
                  kv:
                    statusIntervalMillis: 0
                """, "ringloom.kv.statusIntervalMillis must be > 0");
        assertLoadFails("""
                  kv:
                    stores:
                      prices:
                        sessionPoolSize: 0
                """, "ringloom.kv.stores.prices: kv store sessionPoolSize must be >= 1");
        assertLoadFails("""
                  kv:
                    stores:
                      prices:
                        config:
                          mapSizeHint: -5
                """, "ringloom.kv.stores.prices.config.mapSizeHint must be >= 0");
        assertLoadFails("""
                  kv:
                    stores:
                      "bad/name":
                        reader: true
                """, "ringloom.kv.stores.bad/name: invalid store name (1..64 bytes of [A-Za-z0-9._-])");
    }

    private RingloomApplicationConfig load(String kvYaml) throws Exception {
        Path file = Files.createTempFile(tempDir, "kv", ".yaml");
        Files.writeString(file, HEADER + kvYaml);
        return new YamlRingloomConfigLoader().load(file);
    }

    private void assertLoadFails(String kvYaml, String message) {
        assertThatThrownBy(() -> load(kvYaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }
}
