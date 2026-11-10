// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import io.ringloom.service.KvStatus;

/**
 * Health snapshot of a KV store, combining the framework reader state, the last native reader status
 * and the writer state. Intended for health endpoints; building it may allocate.
 *
 * @param store                the store name
 * @param readerState          framework reader state, or {@code null} when no reader is declared
 * @param readerStatus         native reader status, or {@code null} when unavailable
 * @param writerState          writer lease state, or {@code null} when no writer is declared
 * @param writerEpoch          writer lease epoch ({@code 0} when no writer or not granted)
 * @param statusIntervalMillis configured status refresh interval
 */
public record KvStoreStatus(
        String store,
        KvStoreState readerState,
        KvStatus readerStatus,
        KvWriterState writerState,
        long writerEpoch,
        long statusIntervalMillis) {

    /** Native reader state {@code catching_up}. */
    public static final int NATIVE_CATCHING_UP = 1;
    /** Native reader state {@code live}. */
    public static final int NATIVE_LIVE = 2;

    /**
     * Returns whether the store is healthy.
     *
     * <p>A reader is healthy when it is {@link KvStoreState#OPEN}, the native state is live or
     * catching up, and {@code heartbeatAgeMillis < 3 × statusIntervalMillis} (compared unsigned: the
     * native {@code -1} "applier never beat" marker is unhealthy). A writer-only store is
     * healthy while its writer is not {@link KvWriterState#CLOSED} or {@link KvWriterState#REVOKED}.
     * When both are declared both rules apply.
     *
     * @return {@code true} when healthy
     */
    public boolean healthy() {
        boolean readerHealthy = readerState == null
                || (readerState == KvStoreState.OPEN
                        && readerStatus != null
                        && (readerStatus.state() == NATIVE_LIVE || readerStatus.state() == NATIVE_CATCHING_UP)
                        && Long.compareUnsigned(readerStatus.heartbeatAgeMillis(), 3 * statusIntervalMillis) < 0);
        boolean writerHealthy =
                writerState == null || (writerState != KvWriterState.CLOSED && writerState != KvWriterState.REVOKED);
        return readerHealthy && writerHealthy && (readerState != null || writerState != null);
    }
}
