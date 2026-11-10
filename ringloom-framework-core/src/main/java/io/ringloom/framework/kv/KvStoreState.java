// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

/** Framework view of a KV reader store. */
public enum KvStoreState {
    /** Not opened yet; reads return {@code NOT_READY} and the control thread retries the open. */
    PENDING,
    /** Native reader open; reads go to the local store (which may still be initializing). */
    OPEN,
    /** Open failed with a non-retryable error; reads return {@code NOT_READY} permanently. */
    FAILED,
    /** Closed by runtime shutdown; reads return {@code RingloomHandlerStatus.SHUTDOWN}. */
    CLOSED
}
