// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.kv;

/**
 * Operations a {@code @RingloomKvStore} interface may extend to expose reader state. The generated
 * proxy implements them by delegating to the store's {@link KvReaderStore}.
 */
public interface KvReaderOperations {
    /**
     * Returns the store name.
     *
     * @return the store name
     */
    String storeName();

    /**
     * Returns whether the reader is open and past native initialization.
     *
     * @return {@code true} when reads can be served
     */
    boolean isReady();

    /**
     * Returns a health snapshot of the store. Allocates; intended for health endpoints only.
     *
     * @return the status snapshot
     */
    KvStoreStatus status();
}
