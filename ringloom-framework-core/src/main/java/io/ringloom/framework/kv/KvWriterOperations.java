// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

import io.ringloom.framework.topic.ack.AckCallback;

/**
 * Operations a {@code @RingloomKvStore} interface may extend to expose writer state and batching.
 * The generated proxy implements them by delegating to the store's {@link KvWriterStore}; see that
 * class for the batch threading rules.
 */
public interface KvWriterOperations {
    /**
     * Returns the store name.
     *
     * @return the store name
     */
    String storeName();

    /**
     * Returns the writer lease state (informational; updated on the control thread).
     *
     * @return the writer state
     */
    KvWriterState writerState();

    /**
     * Returns the writer lease epoch.
     *
     * @return the epoch, {@code 0} when not granted
     */
    long writerEpoch();

    /**
     * Opens a batch owned by the calling thread; see {@link KvWriterStore#beginBatch()}.
     *
     * @return the status
     */
    int beginBatch();

    /**
     * Commits the calling thread's batch; see {@link KvWriterStore#commitBatch()}.
     *
     * @return the status
     */
    int commitBatch();

    /**
     * Commits the calling thread's batch with {@code replicate_once}; see
     * {@link KvWriterStore#commitBatchAck(AckCallback, Object)}.
     *
     * @param callback the ack callback
     * @param context  the user context passed to the callback
     * @return the status
     */
    int commitBatchAck(AckCallback callback, Object context);

    /** Discards the calling thread's buffered batch; see {@link KvWriterStore#abandonBatch()}. */
    void abandonBatch();
}
