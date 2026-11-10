// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import static org.assertj.core.api.Assertions.assertThat;

import io.ringloom.service.KvStatus;
import org.junit.jupiter.api.Test;

final class KvStoreStatusTest {
    private static final long INTERVAL = 1000;

    @Test
    void openLiveReaderWithFreshHeartbeatIsHealthy() {
        assertThat(reader(KvStoreState.OPEN, native_(KvStoreStatus.NATIVE_LIVE, 10))
                        .healthy())
                .isTrue();
        assertThat(reader(KvStoreState.OPEN, native_(KvStoreStatus.NATIVE_CATCHING_UP, 2999))
                        .healthy())
                .isTrue();
    }

    @Test
    void staleHeartbeatIsUnhealthy() {
        assertThat(reader(KvStoreState.OPEN, native_(KvStoreStatus.NATIVE_LIVE, 3 * INTERVAL))
                        .healthy())
                .isFalse();
        // -1 (unsigned max) means the applier never beat.
        assertThat(reader(KvStoreState.OPEN, native_(KvStoreStatus.NATIVE_LIVE, -1))
                        .healthy())
                .isFalse();
    }

    @Test
    void nonLiveNativeOrFrameworkStatesAreUnhealthy() {
        assertThat(reader(KvStoreState.OPEN, native_(0, 0)).healthy()).isFalse(); // initializing
        assertThat(reader(KvStoreState.OPEN, native_(3, 0)).healthy()).isFalse(); // rebuilding
        assertThat(reader(KvStoreState.OPEN, native_(4, 0)).healthy()).isFalse(); // failed
        assertThat(reader(KvStoreState.OPEN, null).healthy()).isFalse();
        assertThat(reader(KvStoreState.PENDING, null).healthy()).isFalse();
        assertThat(reader(KvStoreState.FAILED, null).healthy()).isFalse();
        assertThat(reader(KvStoreState.CLOSED, null).healthy()).isFalse();
    }

    @Test
    void writerRulesApply() {
        assertThat(writer(KvWriterState.GRANTED).healthy()).isTrue();
        assertThat(writer(KvWriterState.QUEUED).healthy()).isTrue();
        assertThat(writer(KvWriterState.REASSERTING).healthy()).isTrue();
        assertThat(writer(KvWriterState.REVOKED).healthy()).isFalse();
        assertThat(writer(KvWriterState.CLOSED).healthy()).isFalse();
        assertThat(new KvStoreStatus("s", null, null, null, 0, INTERVAL).healthy())
                .isFalse();
        KvStoreStatus both = new KvStoreStatus(
                "s", KvStoreState.OPEN, native_(KvStoreStatus.NATIVE_LIVE, 0), KvWriterState.REVOKED, 4, INTERVAL);
        assertThat(both.healthy()).isFalse();
    }

    private static KvStoreStatus reader(KvStoreState state, KvStatus status) {
        return new KvStoreStatus("s", state, status, null, 0, INTERVAL);
    }

    private static KvStoreStatus writer(KvWriterState state) {
        return new KvStoreStatus("s", null, null, state, 1, INTERVAL);
    }

    private static KvStatus native_(int state, long heartbeatAgeMillis) {
        return new KvStatus(state, true, 1, 10, 1, 0, 10, 0, heartbeatAgeMillis, 0);
    }
}
